package run.halo.members.utils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;

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
        return resolve(request.remoteAddress()
            .orElseGet(() -> request.exchange().getRequest().getRemoteAddress()),
            request.headers()::firstHeader);
    }

    /**
     * 面向 {@code @RestController} 风格的控制器：它们拿到的是底层请求对象。
     */
    public static String getClientIP(org.springframework.http.server.reactive.ServerHttpRequest request) {
        return resolve(request.getRemoteAddress(),
            name -> request.getHeaders().getFirst(name));
    }

    private static String resolve(InetSocketAddress remoteAddress,
        java.util.function.Function<String, String> firstHeader) {
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            // 只有来自本机/内网反向代理的请求才读取转发头，避免公网调用方伪造 IP。
            if (isTrustedProxy(remoteAddress)) {
                String forwarded = firstForwardedIp(firstHeader.apply("X-Forwarded-For"));
                if (forwarded != null) {
                    return forwarded;
                }
                String realIp = firstForwardedIp(firstHeader.apply("X-Real-IP"));
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

    /**
     * 取转发链里最可信的一跳，并只做字面量解析。
     *
     * <p>取最右值而不是最左值：{@code X-Forwarded-For} 由每一跳代理追加，最左值客户端可以
     * 随意填写，用它做限流 key 等于把限流关掉；最右值是可信代理亲眼看到的对端地址。
     *
     * <p>不做任何 DNS 解析：原实现对 {@code [0-9a-fA-F:]+} 的输入直接调用
     * {@code InetAddress.getByName}，像 {@code X-Forwarded-For: beef} 这种既不是 IP 又长得像
     * 十六进制的值会进入域名解析；本方法在 Netty 事件循环上同步执行，几十个并发就足以把
     * 全站接口拖住。
     */
    private static String firstForwardedIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] hops = value.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String normalized = normalizeLiteral(hops[i].trim());
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * 只接受 IPv4/IPv6 字面量，返回规范化后的文本（仅用于限流 key，不发起网络查询）。
     *
     * @return 规范化地址；不是 IP 字面量时返回 {@code null}
     */
    private static String normalizeLiteral(String candidate) {
        if (candidate.isEmpty() || candidate.length() > 45) {
            return null;
        }
        String value = candidate;
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.indexOf(':') < 0) {
            return normalizeIpv4(value);
        }
        // IPv6：只保留十六进制与分隔符的结构，统一小写，避免同一地址多种写法被当成不同 key
        if (value.contains("%") || value.contains(":::")) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
            if (!allowed) {
                return null;
            }
        }
        if (value.indexOf(':') < 2) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        StringBuilder builder = new StringBuilder(15);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int j = 0; j < part.length(); j++) {
                if (part.charAt(j) < '0' || part.charAt(j) > '9') {
                    return null;
                }
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            if (i > 0) {
                builder.append('.');
            }
            builder.append(octet);
        }
        return builder.toString();
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
