package run.halo.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.members.service.SettingConfigMember;

class SettingConfigMemberTest {

    @Test
    @DisplayName("待审核容量默认 100 且限制非法配置")
    void normalizesPendingCapacity() {
        var config = new SettingConfigMember.BasicConfig();
        assertEquals(100, config.normalizedPendingCapacity());

        config.setPendingCapacity(0);
        assertEquals(100, config.normalizedPendingCapacity());

        config.setPendingCapacity(150_000);
        assertEquals(100_000, config.normalizedPendingCapacity());
    }

    @Test
    @DisplayName("匿名提交默认要求图形验证码")
    void requiresCaptchaByDefault() {
        var config = new SettingConfigMember.BasicConfig();
        assertTrue(config.isEnableSubmitCaptcha());

        config.setEnableSubmitCaptcha(false);
        assertFalse(config.isEnableSubmitCaptcha());
    }
}
