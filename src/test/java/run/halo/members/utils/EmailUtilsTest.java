package run.halo.members.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailUtilsTest {

    @Test
    @DisplayName("规范化会去除空白并统一小写")
    void normalizesCaseAndWhitespace() {
        assertThat(EmailUtils.normalize("  ZhangSan@Example.COM ")).isEqualTo("zhangsan@example.com");
        assertThat(EmailUtils.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("脱敏只保留邮箱前缀和顶级域名")
    void masksLocalPartOnly() {
        assertThat(EmailUtils.mask("zhangsan@example.com")).isEqualTo("zh***@example***.com");
        assertThat(EmailUtils.mask("ab@example.com")).isEqualTo("a***@example***.com");
        assertThat(EmailUtils.mask("  ZhangSan@Example.com ")).isEqualTo("zh***@example***.com");
    }

    @Test
    @DisplayName("非法邮箱原样返回空串或脱敏结果，不抛异常")
    void toleratesMalformedEmail() {
        assertThat(EmailUtils.mask(null)).isEmpty();
        assertThat(EmailUtils.mask("no-at-sign")).isEqualTo("no-at-sign");
        assertThat(EmailUtils.mask("@example.com")).isEqualTo("@example.com");
    }

    @Test
    @DisplayName("未登记邮箱时回退到 QQ 邮箱，登记了邮箱则优先使用登记地址")
    void resolvesQqMailbox() {
        assertThat(EmailUtils.resolveEmail(null, "12345678")).isEqualTo("12345678@qq.com");
        assertThat(EmailUtils.resolveEmail("  ", " 12345678 ")).isEqualTo("12345678@qq.com");
        assertThat(EmailUtils.resolveEmail("ZhangSan@Example.com", "12345678"))
            .isEqualTo("zhangsan@example.com");
    }

    @Test
    @DisplayName("QQ 号不可用时回退结果为空串，由调用方报错")
    void refusesInvalidQqMailbox() {
        assertThat(EmailUtils.resolveEmail(null, null)).isEmpty();
        assertThat(EmailUtils.resolveEmail(null, "abc123")).isEmpty();
        assertThat(EmailUtils.resolveEmail("", "1234")).isEmpty();
    }
}
