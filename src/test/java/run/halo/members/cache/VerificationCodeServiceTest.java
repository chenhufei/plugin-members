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
}
