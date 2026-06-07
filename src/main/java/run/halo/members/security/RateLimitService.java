package run.halo.members.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * API频率限制服务
 * 防止恶意请求和暴力攻击
 * 
 * @author Sky
 * @since 1.1.0
 */
@Slf4j
@Service
public class RateLimitService {
    
    private final Map<String, RequestWindow> requestWindows = new ConcurrentHashMap<>();
    
    // 默认配置：每分钟最多10次请求
    private static final int DEFAULT_MAX_REQUESTS = 10;
    private static final int MAX_REQUESTS_LIMIT = 1000;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    
    /**
     * 检查请求是否被允许
     * 
     * @param clientId 客户端标识（通常是IP地址）
     * @return true如果请求被允许，false如果超出限制
     */
    public boolean isRequestAllowed(String clientId) {
        return isRequestAllowed(clientId, DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW);
    }
    
    /**
     * 检查请求是否被允许（自定义限制）
     * 
     * @param clientId 客户端标识
     * @param maxRequests 最大请求数
     * @param window 时间窗口
     * @return true如果请求被允许，false如果超出限制
     */
    public boolean isRequestAllowed(String clientId, int maxRequests, Duration window) {
        if (clientId == null || clientId.trim().isEmpty()) {
            log.warn("客户端ID为空，拒绝请求");
            return false;
        }
        
        Instant now = Instant.now();
        int normalizedMaxRequests = normalizeMaxRequests(maxRequests);
        Duration normalizedWindow = normalizeWindow(window);
        RequestWindow requestWindow = requestWindows.compute(clientId, (key, existingWindow) -> {
            if (existingWindow == null
                || existingWindow.isExpired(now)
                || !existingWindow.hasSamePolicy(normalizedMaxRequests, normalizedWindow)) {
                return new RequestWindow(now, normalizedMaxRequests, normalizedWindow);
            }
            return existingWindow;
        });
        
        return requestWindow.tryAcquire(now);
    }
    
    /**
     * 获取剩余请求次数
     * 
     * @param clientId 客户端标识
     * @return 剩余请求次数
     */
    public int getRemainingRequests(String clientId) {
        return getRemainingRequests(clientId, DEFAULT_MAX_REQUESTS);
    }

    public int getRemainingRequests(String clientId, int maxRequests) {
        RequestWindow window = requestWindows.get(clientId);
        return window != null ? window.getRemainingRequests() : normalizeMaxRequests(maxRequests);
    }
    
    /**
     * 清理过期的请求窗口
     */
    public void cleanup() {
        Instant now = Instant.now();
        requestWindows.entrySet().removeIf(entry -> 
            entry.getValue().isExpired(now));
    }

    private int normalizeMaxRequests(int maxRequests) {
        if (maxRequests < 1) {
            return DEFAULT_MAX_REQUESTS;
        }
        return Math.min(maxRequests, MAX_REQUESTS_LIMIT);
    }

    private Duration normalizeWindow(Duration window) {
        if (window == null || window.isNegative() || window.isZero()) {
            return DEFAULT_WINDOW;
        }
        return window;
    }
    
    /**
     * 请求窗口类
     */
    private static class RequestWindow {
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final int maxRequests;
        private final Duration window;
        private volatile Instant windowStart;
        
        public RequestWindow(Instant start, int maxRequests, Duration window) {
            this.windowStart = start;
            this.maxRequests = maxRequests;
            this.window = window;
        }
        
        public synchronized boolean tryAcquire(Instant now) {
            // 检查是否需要重置窗口
            if (now.isAfter(windowStart.plus(window))) {
                windowStart = now;
                requestCount.set(0);
            }
            
            int current = requestCount.get();
            if (current >= maxRequests) {
                return false;
            }
            
            requestCount.incrementAndGet();
            return true;
        }
        
        public int getRemainingRequests() {
            return Math.max(0, maxRequests - requestCount.get());
        }

        public boolean hasSamePolicy(int maxRequests, Duration window) {
            return this.maxRequests == maxRequests && this.window.equals(window);
        }
        
        public boolean isExpired(Instant now) {
            return now.isAfter(windowStart.plus(window.multipliedBy(2)));
        }
    }
}
