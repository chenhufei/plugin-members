package run.halo.members.utils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * QQ 加好友链接校验。
 *
 * <p>只接受 QQ 官方分享出来的 {@code https://qm.qq.com/q/<token>} 形式，
 * 避免把任意 http(s) 地址（包括钓鱼站）写进公示卡片和邮件。
 *
 * @since 1.14.4
 */
public final class QqLinkUtils {

    public static final String RULE_MESSAGE = "QQ 加好友链接需为 https://qm.qq.com/q/ 开头的链接";

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
        String path = uri.getPath();
        if (path == null || !path.startsWith("/q/") || path.length() <= 3) {
            return RULE_MESSAGE;
        }
        return null;
    }
}
