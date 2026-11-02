package run.halo.members.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import run.halo.members.Member;

/**
 * 认领验证码收件地址的归属校验。
 *
 * <p>回归的是接管类漏洞：发码与校验原本都接受匿名请求方自填的邮箱，
 * 只要知道成员公开的 QQ 号，就能把验证码发到自己信箱并通过校验，改写该成员资料。
 */
class MemberClaimVerificationEmailTest {

    private static Member member(String email, String qq) {
        Member member = new Member();
        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setEmail(email);
        spec.setQq(qq);
        member.setSpec(spec);
        return member;
    }

    @Test
    @DisplayName("自定义邮箱已关闭：即使登记了别的邮箱，也只发 QQ 邮箱")
    void prefersQqMailbox() {
        assertEquals("10001@qq.com",
            MemberClaimEndpoint.claimVerificationEmail(member("Owner@Example.com", "10001"), "", "10001"));
    }

    @Test
    @DisplayName("QQ 号无法拼出邮箱时回退到成员登记邮箱，避免老数据永久无法认领")
    void fallsBackToRegisteredEmailWhenQqInvalid() {
        assertEquals("owner@example.com",
            MemberClaimEndpoint.claimVerificationEmail(
                member("Owner@Example.com", "not-a-qq"), "", "not-a-qq"));
        assertEquals("10001@qq.com",
            MemberClaimEndpoint.claimVerificationEmail(member("  ", "10001"), "10001@qq.com", "10001"));
    }

    @Test
    @DisplayName("第三方邮箱一律不接受")
    void rejectsForeignMailbox() {
        assertNull(MemberClaimEndpoint.claimVerificationEmail(
            member("owner@example.com", "10001"), "attacker@evil.test", "10001"));
        assertNull(MemberClaimEndpoint.claimVerificationEmail(
            member(null, "10001"), "attacker@evil.test", "10001"));
    }

    @Test
    @DisplayName("成员无任何可用邮箱时返回空串，由调用方拒绝而不是跳过校验")
    void returnsEmptyWhenNoMailboxAvailable() {
        assertEquals("", MemberClaimEndpoint.claimVerificationEmail(member(null, "not-a-qq"), "", "not-a-qq"));
    }
}
