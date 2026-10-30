package run.halo.members.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * 邮箱规范化与脱敏。
 *
 * <p>验证码邮件的收件地址来自成员记录，向匿名调用方只回显脱敏结果，
 * 既让用户知道该查哪个信箱，又不让接口成为成员邮箱的批量导出通道。
 *
 * @since 1.14.0
 */
public final class EmailUtils {

    private EmailUtils() {
    }

    public static String normalize(String rawEmail) {
        return StringUtils.trimToEmpty(rawEmail).toLowerCase();
    }

    /**
     * 成员未登记邮箱时的收件地址：使用 QQ 号对应的 QQ 邮箱。
     *
     * <p>认领验证码和认领链接都发给成员本人，QQ 邮箱是 QQ 号天然自带的地址，
     * 因此没有登记邮箱时直接回退，而不是让流程卡在「联系管理员」。
     *
     * @return 规范化邮箱；QQ 号缺失或格式不合法时返回空字符串
     */
    public static String resolveEmail(String rawEmail, String qq) {
        String email = normalize(rawEmail);
        if (!email.isEmpty()) {
            return email;
        }
        String value = StringUtils.trimToEmpty(qq);
        if (!value.matches("\\d{5,12}")) {
            return "";
        }
        return value + "@qq.com";
    }

    public static String mask(String email) {
        String normalized = normalize(email);
        int at = normalized.indexOf('@');
        if (at <= 0) {
            return normalized;
        }
        String local = normalized.substring(0, at);
        String domain = normalized.substring(at + 1);
        String maskedLocal = local.length() <= 2
            ? local.charAt(0) + "***"
            : local.substring(0, 2) + "***";
        int dot = domain.indexOf('.');
        String maskedDomain = dot > 0
            ? domain.substring(0, dot) + "***" + domain.substring(dot)
            : domain;
        return maskedLocal + "@" + maskedDomain;
    }
}
