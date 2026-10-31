package run.halo.members.utils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * QQ 加好友链接校验。
 *
 * <p>只接受 QQ 官方分享出来的加好友链接，避免把任意 http(s) 地址
 * （包括钓鱼站）写进公示卡片和邮件。官方目前有两种形态：
 * <ul>
 *   <li>{@code https://qm.qq.com/q/<token>}（新版分享链接）</li>
 *   <li>{@code https://qm.qq.com/cgi-bin/qm/qr?k=<token>...}（二维码里编码的旧版链接）</li>
 * </ul>
 * 二维码解析出来的往往是后者，以前只认 {@code /q/} 前缀，导致"扫出来了却说不是 QQ 链接"。
 *
 * @since 1.14.4
 */
public final class QqLinkUtils {

    public static final String RULE_MESSAGE = "QQ 加好友链接需为 qm.qq.com 的官方链接（如 https://qm.qq.com/q/xxxx）";

    /** 新版短链前缀：token 在路径里，必须非空 */
    private static final String SHORT_PATH_PREFIX = "/q/";

    /** 旧版二维码链接路径：token 在查询参数 k 里，路径本身没有 token */
    private static final String QR_PATH = "/cgi-bin/qm/qr";

    private QqLinkUtils() {
    }

    /**
     * @return 校验失败时的错误信息；通过时返回 null
     */
    public static String validate(String qqFriendLink) {
        if (qqFriendLink == null || qqFriendLink.isBlank()) {
            return "QQ好友链接不能为空";
        }
        if (qqFriendLink.length() > 500) {
            return "QQ好友链接不能超过500个字符";
        }
        URI uri;
        try {
            uri = new URI(qqFriendLink.trim());
        } catch (URISyntaxException error) {
            return "QQ好友链接格式不正确";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return RULE_MESSAGE;
        }
        if (!"qm.qq.com".equalsIgnoreCase(uri.getHost())) {
            return RULE_MESSAGE;
        }
        return isOfficialPath(uri.getPath(), uri.getQuery()) ? null : RULE_MESSAGE;
    }

    /**
     * 判定官方路径。
     *
     * <p>旧版二维码链接的路径恰好就是 {@code /cgi-bin/qm/qr}，token 放在 {@code k} 查询参数里，
     * 所以不能再要求"路径比前缀长"——那会把 QQ 客户端导出的二维码链接全部判成非法，
     * 这正是之前"扫出来了却说不是 QQ 链接"的根因。</p>
     */
    private static boolean isOfficialPath(String path, String query) {
        if (path == null) {
            return false;
        }
        if (path.startsWith(SHORT_PATH_PREFIX) && path.length() > SHORT_PATH_PREFIX.length()) {
            return true;
        }
        if (!path.equals(QR_PATH) && !path.startsWith(QR_PATH + "/")) {
            return false;
        }
        if (query == null) {
            return false;
        }
        for (String parameter : query.split("&")) {
            if (parameter.startsWith("k=") && parameter.length() > 2) {
                return true;
            }
        }
        return false;
    }
}
