package run.halo.members.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.members.Member;
import run.halo.members.MemberConstant;
import run.halo.members.finders.impl.MemberFinderImpl;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.service.SettingConfigMember;

class MemberWithdrawReviewServiceImplTest {

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final SettingConfigMember settingConfigMember = mock(SettingConfigMember.class);
    private final MemberFinderImpl memberFinder = mock(MemberFinderImpl.class);
    private final NotificationReasonEmitter reasonEmitter = mock(NotificationReasonEmitter.class);
    private final NotificationCenter notificationCenter = mock(NotificationCenter.class);
    private final ExternalLinkProcessor externalLinkProcessor =
        mock(ExternalLinkProcessor.class);

    private final MemberWithdrawReviewServiceImpl service =
        new MemberWithdrawReviewServiceImpl(client, settingConfigMember, memberFinder,
            reasonEmitter, notificationCenter, externalLinkProcessor);

    @Test
    @DisplayName("批准撤回后成员下架并记录下架注解")
    void approveTakesMemberOffline() {
        var member = withdrawRequested("m1", "APPROVED", "不想继续了");
        when(client.fetch(Member.class, "m1")).thenReturn(Mono.just(member));
        stubUpdate();

        var result = service.review("m1", true).block();

        assertThat(result).isNotNull();
        assertThat(result.statusBefore()).isEqualTo("APPROVED");
        assertThat(result.message()).contains("成员已下架");
        assertThat(member.getSpec().getStatus()).isEqualTo("REJECTED");
        assertThat(member.getMetadata().getAnnotations())
            .containsEntry(MemberConstant.WITHDRAW_REVIEW_ACTION, "APPROVED")
            .containsEntry(MemberConstant.REVIEW_ACTION, MemberConstant.REVIEW_ACTION_WITHDRAW)
            .containsKey(MemberConstant.OFFLINE_AT)
            .containsEntry(MemberConstant.OFFLINE_SOURCE, "SELF_WITHDRAW");
    }

    @Test
    @DisplayName("拒绝撤回后恢复撤回前的状态")
    void rejectRestoresPreviousStatus() {
        var member = withdrawRequested("m2", "PENDING", "暂时离开");
        when(client.fetch(Member.class, "m2")).thenReturn(Mono.just(member));
        stubUpdate();

        var result = service.review("m2", false).block();

        assertThat(result).isNotNull();
        assertThat(result.message()).contains("恢复为PENDING");
        assertThat(member.getSpec().getStatus()).isEqualTo("PENDING");
        assertThat(member.getMetadata().getAnnotations())
            .containsEntry(MemberConstant.WITHDRAW_REVIEW_ACTION, "REJECTED")
            .containsEntry(MemberConstant.REVIEW_ACTION,
                MemberConstant.REVIEW_ACTION_WITHDRAW_REJECT);
    }

    @Test
    @DisplayName("非撤回申请中的成员不能再次审核")
    void nonPendingWithdrawIsRejected() {
        var member = member("m3", "APPROVED");
        when(client.fetch(Member.class, "m3")).thenReturn(Mono.just(member));

        assertThrows(MemberWithdrawReviewService.AlreadyReviewedException.class,
            () -> service.review("m3", true).block());
        verify(client, org.mockito.Mockito.never()).update(any());
    }

    @Test
    @DisplayName("不存在的成员抛出 NOT_FOUND 语义异常")
    void missingMemberIsRejected() {
        when(client.fetch(Member.class, "ghost")).thenReturn(Mono.empty());

        assertThrows(MemberWithdrawReviewService.MemberNotFoundException.class,
            () -> service.review("ghost", true).block());
    }

    @Test
    @DisplayName("并发「批准」与「拒绝」只生效一次：一次更新、一封通知")
    void concurrentReviewAppliesOnlyOnce() {
        var member = withdrawRequested("m4", "APPROVED", "并发测试");
        // 加一点延迟把竞争窗口撑开，否则两个请求可能天然错开、测不出问题
        when(client.fetch(Member.class, "m4"))
            .thenAnswer(invocation -> Mono.delay(Duration.ofMillis(20)).thenReturn(member));
        when(client.update(any())).thenAnswer(invocation ->
            Mono.delay(Duration.ofMillis(20)).thenReturn(invocation.getArgument(0)));
        var config = new SettingConfigMember.BasicConfig();
        config.setSendEmail(true);
        when(settingConfigMember.getBasicConfig()).thenReturn(Mono.just(config));
        when(notificationCenter.subscribe(any(), any())).thenReturn(Mono.empty());
        when(externalLinkProcessor.processLink(anyString())).thenReturn("/members");
        when(reasonEmitter.emit(any(), any())).thenReturn(Mono.empty());

        var approve = service.review("m4", true).subscribeOn(Schedulers.boundedElastic());
        var reject = service.review("m4", false).subscribeOn(Schedulers.boundedElastic());

        var signals = Flux.merge(approve.materialize(), reject.materialize())
            .collectList()
            .block();

        assertThat(signals).hasSize(2);
        assertThat(signals.stream().filter(Signal::isOnNext)).hasSize(1);
        assertThat(signals.stream()
            .filter(Signal::isOnError)
            .map(signal -> signal.getThrowable())
            .filter(error -> error instanceof MemberWithdrawReviewService.AlreadyReviewedException))
            .as("后到的请求应因状态已变更被判为「已处理」")
            .hasSize(1);

        // 这正是修复前的表现：两次 update + 两封内容矛盾的通知
        verify(client, times(1)).update(any());
        verify(reasonEmitter, times(1)).emit(any(), any());
    }

    private void stubUpdate() {
        when(client.update(any())).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));
        when(settingConfigMember.getBasicConfig()).thenReturn(Mono.just(
            new SettingConfigMember.BasicConfig()));
    }

    private static Member withdrawRequested(String name, String statusBefore,
        String reason) {
        var member = member(name, "WITHDRAW_REQUESTED");
        var annotations = member.getMetadata().getAnnotations();
        annotations.put(MemberConstant.WITHDRAW_STATUS_BEFORE, statusBefore);
        annotations.put(MemberConstant.WITHDRAW_REASON, reason);
        return member;
    }

    private static Member member(String name, String status) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setAnnotations(new HashMap<>());
        var spec = new Member.MemberSpec();
        spec.setDisplayName("成员" + name);
        spec.setEmail(name + "@example.com");
        spec.setStatus(status);
        var member = new Member();
        member.setMetadata(metadata);
        member.setSpec(spec);
        return member;
    }
}
