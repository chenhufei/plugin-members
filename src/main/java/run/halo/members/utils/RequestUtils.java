package run.halo.members.utils;

import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * HTTP 请求工具类
 * 提取客户端信息的通用方法
 * 
 * @author Sky
 * @since 2.0.0
 */
public class RequestUtils {

    private RequestUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 获取客户端IP地址
     * 支持代理和负载均衡场景
     * 
     * @param request ServerRequest
     * @return 客户端IP地址
     */
    public static String getClientIP(ServerRequest request) {
        // 尝试从 X-Forwarded-For 头部获取真实IP
        String xForwardedFor = request.headers().firstHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        // 尝试从 X-Real-IP 头部获取
        String xRealIP = request.headers().firstHeader("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }
        
        // 从远程地址获取
        return request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
    }

    /**
     * 获取User-Agent
     * 
     * @param request ServerRequest
     * @return User-Agent字符串
     */
    public static String getUserAgent(ServerRequest request) {
        return request.headers().firstHeader("User-Agent");
    }

    /**
     * 获取Referer
     * 
     * @param request ServerRequest
     * @return Referer字符串
     */
    public static String getReferer(ServerRequest request) {
        return request.headers().firstHeader("Referer");
    }
}
