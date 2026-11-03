package run.halo.members.utils;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 头像代理的地址白名单。
 * <p>
 * 浏览器把跨域图片画进画布时要求 CORS，而腾讯的 QQ 头像 CDN 不返回
 * {@code Access-Control-Allow-Origin}，<code>&lt;img&gt;</code> 能显示、画布却拿不到像素，
 * 导出的工作证图片上就只剩一个占位字母。这里给前端一个有地址白名单的服务端代理，
 * 让它以同源地址取到头像字节。
 * <p>
 * 白名单是硬约束：只允许 QQ 头像的固定主机与固定路径，禁止改协议、改端口、
 * 携带 userinfo 或跳转到别处，避免匿名接口被当成通用的 SSRF 跳板。
 */
public final class AvatarProxyUrl {

    /** QQ 头像实际会出现的几个主机，其余一律拒绝。 */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "q1.qlogo.cn",
        "q2.qlogo.cn",
        "q3.qlogo.cn",
        "q.qlogo.cn",
        "thirdqq.qlogo.cn");

    /** 只有这两个路径提供头像图片。 */
    private static final Set<String> ALLOWED_PATHS = Set.of("/g", "/headimg_dl");

    /** 头像地址本身的长度上限，挡住超长参数刷日志。 */
    private static final int MAX_URL_LENGTH = 512;

    private AvatarProxyUrl() {
    }

    /**
     * 校验并归一化待代理的头像地址。
     *
     * @param raw 前端传来的原始地址
     * @return 通过白名单的 URI；不合法时返回 {@link Optional#empty()}
     */
    public static Optional<URI> resolve(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Optional.empty();
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        if (uri.getPath() == null || !ALLOWED_PATHS.contains(uri.getPath())) {
            return Optional.empty();
        }
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }
}
