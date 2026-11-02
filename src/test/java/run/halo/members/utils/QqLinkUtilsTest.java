package run.halo.members.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QqLinkUtilsTest {

    @Test
    @DisplayName("接受 QQ 官方分享的加好友链接")
    void acceptsOfficialLink() {
        assertThat(QqLinkUtils.validate("https://qm.qq.com/q/AbCd1234")).isNull();
        assertThat(QqLinkUtils.validate("  https://qm.qq.com/q/AbCd1234  ")).isNull();
        assertThat(QqLinkUtils.validate("https://QM.QQ.com/q/AbCd1234")).isNull();
    }

    @Test
    @DisplayName("拒绝空链接与非 QQ 域名")
    void rejectsBlankAndForeignHosts() {
        assertThat(QqLinkUtils.validate(null)).isEqualTo("QQ好友链接不能为空");
        assertThat(QqLinkUtils.validate("   ")).isEqualTo("QQ好友链接不能为空");
        assertThat(QqLinkUtils.validate("https://example.com/q/abc"))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
        assertThat(QqLinkUtils.validate("https://qm.qq.com.evil.com/q/abc"))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
    }

    @Test
    @DisplayName("接受二维码里编码的 cgi-bin 链接（token 在 k 参数里）")
    void acceptsQrEncodedLink() {
        assertThat(QqLinkUtils.validate("https://qm.qq.com/cgi-bin/qm/qr?k=abc")).isNull();
        assertThat(QqLinkUtils.validate(
            "https://qm.qq.com/cgi-bin/qm/qr?k=abc&jump_from=webapi")).isNull();
        assertThat(QqLinkUtils.validate(
            "https://qm.qq.com/cgi-bin/qm/qr?jump_from=webapi&k=abc")).isNull();
    }

    @Test
    @DisplayName("拒绝 http、缺少 token 的路径与 query")
    void rejectsWrongSchemeOrPath() {
        assertThat(QqLinkUtils.validate("http://qm.qq.com/q/AbCd1234"))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
        assertThat(QqLinkUtils.validate("https://qm.qq.com/q/"))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
        // 路径对但没有 k 参数：旧版二维码链接必须带 token
        assertThat(QqLinkUtils.validate("https://qm.qq.com/cgi-bin/qm/qr"))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
        assertThat(QqLinkUtils.validate("https://qm.qq.com/cgi-bin/qm/qr?k="))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
        assertThat(QqLinkUtils.validate("https://qm.qq.com/cgi-bin/qm/other?k=abc"))
            .isEqualTo(QqLinkUtils.RULE_MESSAGE);
        assertThat(QqLinkUtils.validate("not a url")).isEqualTo("QQ好友链接格式不正确");
    }
}
