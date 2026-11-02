package run.halo.members.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VerificationCodeServiceTest {

    @Test
    void codeIsBoundToTheMemberIdentityAndCanOnlyBeUsedOnce() {
        VerificationCodeService service = new VerificationCodeService();
        String code = service.generateCode("member@example.com:10001");

        assertFalse(service.verifyCode("member@example.com:10002", code));
        assertTrue(service.verifyCode("member@example.com:10001", code));
        assertFalse(service.verifyCode("member@example.com:10001", code));
    }

    @Test
    void invalidateLetsTheUserRequestANewCodeAfterTheMailFailed() {
        VerificationCodeService service = new VerificationCodeService();
        String key = "member@example.com:10001";
        service.generateCode(key);

        assertTrue(service.hasValidCode(key));
        service.invalidate(key);
        assertFalse(service.hasValidCode(key));
        assertFalse(service.verifyCode(key, "000000"));
    }

    @Test
    void invalidatesTheCodeAfterTooManyFailedAttempts() {
        VerificationCodeService service = new VerificationCodeService();
        String key = "member@example.com:10001";
        String code = service.generateCode(key);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertFalse(service.verifyCode(key, wrong));
        }

        // 达到上限后条目直接作废，正确验证码也不再放行，避免 6 位数字被穷举
        assertFalse(service.hasValidCode(key));
        assertFalse(service.verifyCode(key, code));
    }

    @Test
    void keepsTheCodeUsableWithinTheAttemptBudget() {
        VerificationCodeService service = new VerificationCodeService();
        String key = "member@example.com:10001";
        String code = service.generateCode(key);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            assertFalse(service.verifyCode(key, wrong));
        }

        // 第 5 次才作废，之前输对仍然有效，上限不能误伤正常输入错误
        assertTrue(service.verifyCode(key, code));
    }
}
