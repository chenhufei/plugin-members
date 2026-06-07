package run.halo.members.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 缓存服务
 * 提供内存缓存功能，减少重复查询
 * 
 * @author Sky
 * @since 2.0.0
 */
@Slf4j
@Service
public class CacheService {

    private static final int MAX_ENTRIES = 512;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    /**
     * 存储缓存
     * 
     * @param key 缓存键
     * @param value 缓存值
     */
    public void put(String key, Object value) {
        put(key, value, DEFAULT_TTL);
    }
    
    /**
     * 存储缓存（自定义TTL）
     * 
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl 生存时间
     */
    public void put(String key, Object value, Duration ttl) {
        if (key == null || value == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        
        Instant now = Instant.now();
        cleanup(now);
        if (!cache.containsKey(key) && cache.size() >= MAX_ENTRIES) {
            evictOldest();
        }

        cache.put(key, new CacheEntry(value, now, now.plus(ttl)));
        
        log.debug("缓存已存储: key={}, ttl={}", key, ttl);
    }
    
    /**
     * 获取缓存
     * 
     * @param key 缓存键
     * @param type 值类型
     * @return 缓存值，如果不存在或已过期则返回null
     */
    public <T> T get(String key, Class<T> type) {
        if (key == null || type == null) {
            return null;
        }
        
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            log.debug("缓存未命中: key={}", key);
            return null;
        }
        
        if (entry.isExpired()) {
            cache.remove(key);
            log.debug("缓存已过期: key={}", key);
            return null;
        }
        
        Object value = entry.getValue();
        if (!type.isInstance(value)) {
            log.warn("缓存类型转换失败: key={}, expectedType={}, actualType={}",
                key, type.getSimpleName(), value.getClass().getSimpleName());
            cache.remove(key);
            return null;
        }

        try {
            log.debug("缓存命中: key={}", key);
            return type.cast(value);
        } catch (ClassCastException e) {
            cache.remove(key);
            return null;
        }
    }
    
    /**
     * 删除缓存
     * 
     * @param key 缓存键
     */
    public void evict(String key) {
        if (key != null) {
            cache.remove(key);
            log.debug("缓存已删除: key={}", key);
        }
    }
    
    /**
     * 删除匹配模式的缓存
     * 
     * @param pattern 键模式（支持*通配符）
     */
    public void evictByPattern(String pattern) {
        if (pattern == null) {
            return;
        }
        
        Pattern regex = Pattern.compile(toRegex(pattern));
        AtomicInteger removedCount = new AtomicInteger(0);
        
        cache.entrySet().removeIf(entry -> {
            if (regex.matcher(entry.getKey()).matches()) {
                removedCount.incrementAndGet();
                return true;
            }
            return false;
        });
        
        log.debug("按模式删除缓存: pattern={}, removedCount={}", pattern, removedCount.get());
    }
    
    /**
     * 清空所有缓存
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        log.info("已清空所有缓存: count={}", size);
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanup() {
        cleanup(Instant.now());
    }

    private void cleanup(Instant now) {
        AtomicInteger removedCount = new AtomicInteger(0);
        
        cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired(now)) {
                removedCount.incrementAndGet();
                return true;
            }
            return false;
        });
        
        if (removedCount.get() > 0) {
            log.debug("清理过期缓存: removedCount={}", removedCount.get());
        }
    }

    private void evictOldest() {
        cache.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getValue().createdTime()))
            .map(Map.Entry::getKey)
            .ifPresent(cache::remove);
    }

    private String toRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        return regex.toString();
    }
    
    /**
     * 获取缓存统计信息
     * 
     * @return 缓存统计
     */
    public CacheStats getStats() {
        cleanup(); // 先清理过期缓存
        
        return CacheStats.builder()
            .totalEntries(cache.size())
            .build();
    }
    
    /**
     * 缓存条目
     */
    private static class CacheEntry {
        private final Object value;
        private final Instant createdTime;
        private final Instant expireTime;
        
        public CacheEntry(Object value, Instant createdTime, Instant expireTime) {
            this.value = value;
            this.createdTime = createdTime;
            this.expireTime = expireTime;
        }
        
        public Object getValue() {
            return value;
        }

        public Instant createdTime() {
            return createdTime;
        }
        
        public boolean isExpired() {
            return isExpired(Instant.now());
        }
        
        public boolean isExpired(Instant now) {
            return now.isAfter(expireTime);
        }
    }
    
    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        private final int totalEntries;
        
        private CacheStats(int totalEntries) {
            this.totalEntries = totalEntries;
        }
        
        public static CacheStatsBuilder builder() {
            return new CacheStatsBuilder();
        }
        
        public int getTotalEntries() {
            return totalEntries;
        }
        
        public static class CacheStatsBuilder {
            private int totalEntries;
            
            public CacheStatsBuilder totalEntries(int totalEntries) {
                this.totalEntries = totalEntries;
                return this;
            }
            
            public CacheStats build() {
                return new CacheStats(totalEntries);
            }
        }
    }
}
