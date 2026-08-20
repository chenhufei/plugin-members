package run.halo.members.utils;

import java.net.InetAddress;
import java.net.InetSocketAddress;

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
        var remoteAddress = request.remoteAddress()
            .orElseGet(() -> request.exchange().getRequest().getRemoteAddress());
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            // 只有来自本机/内网反向代理的请求才读取转发头，避免公网调用方伪造 IP。
            if (isTrustedProxy(remoteAddress)) {
                String forwarded = firstForwardedIp(request.headers().firstHeader("X-Forwarded-For"));
                if (forwarded != null) {
                    return forwarded;
                }
                String realIp = firstForwardedIp(request.headers().firstHeader("X-Real-IP"));
                if (realIp != null) {
                    return realIp;
                }
            }
            return remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static boolean isTrustedProxy(InetSocketAddress remoteAddress) {
        InetAddress address = remoteAddress.getAddress();
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || isUniqueLocalIpv6(address);
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String firstForwardedIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.split(",", 2)[0].trim();
        if (candidate.isBlank() || !candidate.matches("(?:\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F:]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address.getHostAddress();
        } catch (Exception e) {
            return null;
        }
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
