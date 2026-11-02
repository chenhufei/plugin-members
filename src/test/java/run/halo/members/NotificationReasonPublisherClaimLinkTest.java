package run.halo.members;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.notification.NotificationCenter;
import run.halo.members.NotificationReasonPublisher.ClaimLinkNoticeReasonPublisher;
import run.halo.members.NotificationReasonPublisher.NoMemberEmailException;
import run.halo.members.service.SettingConfigMember;

/**
 * 认领链接邮件的响应式契约测试：不得在-reactive 链里阻塞，也不得伪造收件邮箱。
 */
@ExtendWith(MockitoExtension.class)
class NotificationReasonPublisherClaimLinkTest {

    @Mock
    private ExtensionClient client;
    @Mock
    private SettingConfigMember settingConfigMember;
    @Mock
    private NotificationCenter notificationCenter;
    @Mock
    private ClaimLinkNoticeReasonPublisher claimLinkNoticeReasonPublisher;

    private NotificationReasonPublisher publisher() {
        return new NotificationReasonPublisher(client, settingConfigMember, notificationCenter,
            mock(NotificationReasonPublisher.AdminMemberSubmitNoticeReasonPublisher.class),
            mock(NotificationReasonPublisher.UserMemberSubmitNoticeReasonPublisher.class),
            mock(NotificationReasonPublisher.ReviewMemberSubmitNoticeReasonPublisher.class),
            mock(NotificationReasonPublisher.ReviewMemberRejectNoticeReasonPublisher.class),
            mock(NotificationReasonPublisher.ReviewMemberOfflineNoticeReasonPublisher.class),
            claimLinkNoticeReasonPublisher);
    }

    private static Member memberWithEmail(String email) {
        var metadata = new Metadata();
        metadata.setName("member-1");
        var spec = new Member.MemberSpec();
        spec.setDisplayName("张三");
        spec.setQq("12345678");
        spec.setEmail(email);
        var member = new Member();
        member.setMetadata(metadata);
        member.setSpec(spec);
        return member;
    }

    @Test
    @DisplayName("成员未登记邮箱时自动回退到 QQ 邮箱")
    void fallsBackToQqMailbox() {
        var publisher = publisher();
        var member = memberWithEmail(null);
        when(notificationCenter.subscribe(any(Subscription.Subscriber.class),
            any(Subscription.InterestReason.class))).thenReturn(Mono.empty());
        when(claimLinkNoticeReasonPublisher.publishReasonBy(eq(member), eq("12345678@qq.com"),
            eq("https://site/claim?token=t"))).thenReturn(Mono.empty());

        var outcome = publisher.publishClaimLinkNotice(member, "https://site/claim?token=t")
            .then(Mono.just("sent"))
            .block(Duration.ofSeconds(5));

        assertThat(outcome).isEqualTo("sent");
        verify(claimLinkNoticeReasonPublisher).publishReasonBy(member, "12345678@qq.com",
            "https://site/claim?token=t");
    }

    @Test
    @DisplayName("邮箱与 QQ 号都不可用时返回错误信号，不伪造收件地址")
    void failsWhenNoMailboxAvailable() {
        var publisher = publisher();
        var member = memberWithEmail(null);
        member.getSpec().setQq("not-a-qq");

        assertThatThrownBy(() -> publisher.publishClaimLinkNotice(member, "https://site/claim?token=t")
            .block(Duration.ofSeconds(5)))
            .isInstanceOf(NoMemberEmailException.class);

        verifyNoInteractions(notificationCenter, claimLinkNoticeReasonPublisher);
    }

    @Test
    @DisplayName("发送认领链接通知全程非阻塞，可在 Reactor NonBlocking 线程上完成")
    void sendsWithoutBlockingTheEventLoop() {
        var publisher = publisher();
        var member = memberWithEmail("zhangsan@example.com");
        when(notificationCenter.subscribe(any(Subscription.Subscriber.class),
            any(Subscription.InterestReason.class))).thenReturn(Mono.empty());
        when(claimLinkNoticeReasonPublisher.publishReasonBy(eq(member),
            eq("zhangsan@example.com"), eq("https://site/claim?token=t")))
            .thenReturn(Mono.empty());

        // 任何内部 block() 都会在 parallel 调度器的 NonBlocking 线程上直接抛 IllegalStateException
        var outcome = publisher.publishClaimLinkNotice(member, "https://site/claim?token=t")
            .then(Mono.just("sent"))
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(5));

        assertThat(outcome).isEqualTo("sent");
        verify(claimLinkNoticeReasonPublisher).publishReasonBy(member, "zhangsan@example.com",
            "https://site/claim?token=t");
    }

    @Test
    @DisplayName("订阅失败不阻断通知提交，仍会发出认领链接原因")
    void subscriptionFailureDoesNotSwallowTheNotice() {
        var publisher = publisher();
        var member = memberWithEmail("zhangsan@example.com");
        when(notificationCenter.subscribe(any(Subscription.Subscriber.class),
            any(Subscription.InterestReason.class)))
            .thenReturn(Mono.error(new IllegalStateException("订阅失败")));
        when(claimLinkNoticeReasonPublisher.publishReasonBy(any(), any(), any()))
            .thenReturn(Mono.empty());

        var outcome = publisher.publishClaimLinkNotice(member, "https://site/claim?token=t")
            .then(Mono.just("sent"))
            .block(Duration.ofSeconds(5));

        assertThat(outcome).isEqualTo("sent");
    }
}
