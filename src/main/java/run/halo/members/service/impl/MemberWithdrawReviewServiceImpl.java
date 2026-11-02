package run.halo.members.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.members.Member;
import run.halo.members.finders.impl.MemberFinderImpl;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.service.SettingConfigMember;

import static run.halo.members.MemberConstant.OFFLINE_AT;
import static run.halo.members.MemberConstant.OFFLINE_REASON;
import static run.halo.members.MemberConstant.OFFLINE_SOURCE;
import static run.halo.members.MemberConstant.REVIEW_ACTION;
import static run.halo.members.MemberConstant.REVIEW_ACTION_WITHDRAW;
import static run.halo.members.MemberConstant.REVIEW_ACTION_WITHDRAW_REJECT;
import static run.halo.members.MemberConstant.REVIEW_DESCRIPTION;
import static run.halo.members.MemberConstant.USER_MEMBER_WITHDRAW_REVIEW;
import static run.halo.members.MemberConstant.WITHDRAW_REASON;
import static run.halo.members.MemberConstant.WITHDRAW_REVIEW_ACTION;
import static run.halo.members.MemberConstant.WITHDRAW_REVIEWED_AT;
import static run.halo.members.MemberConstant.WITHDRAW_STATUS_BEFORE;

/**
 * 撤回申请审核服务实现。
 *
 * @since 1.6.1
 */
@Service
@RequiredArgsConstructor
public class MemberWithdrawReviewServiceImpl implements MemberWithdrawReviewService {

    private static final String STATUS_WITHDRAW_REQUESTED = "WITHDRAW_REQUESTED";

    /** 后到的审核请求等待前一个结束的上限，超时直接返回「正在处理中」。 */
    private static final Duration REVIEW_LOCK_WAIT = Duration.ofSeconds(5);

    /**
     * 同一成员的审核串行化。
     *
     * <p>「批准」和「拒绝」同时到达时，两个请求都会 fetch 到 WITHDRAW_REQUESTED，都通过状态检查，
     * 然后各自 update 并各自发通知 —— 成员会收到一封「已下架」和一封「已恢复」。
     * 这里按成员名加互斥：后到者等前一个完成后重新进入，此时状态已不是待审核，
     * 会走 AlreadyReviewedException，只有一封通知。
     *
     * <p>只覆盖本进程；多实例部署时仍由资源 version 冲突兜底（见 update 的 onErrorMap）。
     */
    private final Map<String, Sinks.Empty<Void>> reviewLocks = new ConcurrentHashMap<>();

    private final ReactiveExtensionClient client;
    private final SettingConfigMember settingConfigMember;
    private final MemberFinderImpl memberFinder;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;

    @Override
    public Mono<ReviewResult> review(String memberName, boolean approved) {
        return serializeByMember(memberName, () -> doReview(memberName, approved));
    }

    /**
     * 用「占位 + 等待」的方式把同名请求排成队，不占用线程、不阻塞事件循环。
     */
    private Mono<ReviewResult> serializeByMember(String memberName,
        Supplier<Mono<ReviewResult>> action) {
        return Mono.defer(() -> {
            Sinks.Empty<Void> mine = Sinks.empty();
            Sinks.Empty<Void> running = reviewLocks.putIfAbsent(memberName, mine);
            if (running == null) {
                return action.get()
                    .doFinally(signal -> {
                        reviewLocks.remove(memberName, mine);
                        mine.tryEmitEmpty();
                    });
            }
            return running.asMono()
                .timeout(REVIEW_LOCK_WAIT)
                .onErrorMap(TimeoutException.class,
                    error -> new ReviewInProgressException(memberName))
                // 前一个已结束且释放了占位，重新进入即可拿到最新状态
                .then(Mono.defer(() -> serializeByMember(memberName, action)));
        });
    }

    private Mono<ReviewResult> doReview(String memberName, boolean approved) {
        return client.fetch(Member.class, memberName)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(memberName)))
            .flatMap(member -> {
                if (!STATUS_WITHDRAW_REQUESTED.equals(member.getSpec().getStatus())) {
                    return Mono.error(new AlreadyReviewedException(memberName));
                }

                var annotations = MetadataUtil.nullSafeAnnotations(member);
                String statusBefore = annotations.getOrDefault(WITHDRAW_STATUS_BEFORE, "PENDING");
                String email = member.getSpec().getEmail();
                String displayName = member.getSpec().getDisplayName();
                String withdrawReason =
                    StringUtils.defaultString(annotations.get(WITHDRAW_REASON));
                String reviewedAt = Instant.now().toString();

                annotations.put(WITHDRAW_REVIEW_ACTION, approved ? "APPROVED" : "REJECTED");
                annotations.put(WITHDRAW_REVIEWED_AT, reviewedAt);
                if (approved) {
                    String reason = StringUtils.defaultIfBlank(withdrawReason, "成员主动撤回");
                    member.getSpec().setStatus("REJECTED");
                    annotations.put(REVIEW_ACTION, REVIEW_ACTION_WITHDRAW);
                    annotations.put(REVIEW_DESCRIPTION, reason);
                    annotations.put(OFFLINE_AT, reviewedAt);
                    annotations.put(OFFLINE_REASON, reason);
                    annotations.put(OFFLINE_SOURCE, "SELF_WITHDRAW");
                } else {
                    member.getSpec().setStatus(statusBefore);
                    annotations.put(REVIEW_ACTION, REVIEW_ACTION_WITHDRAW_REJECT);
                }

                return client.update(member)
                    // 二次防线：多实例下两个进程各自持有自己的锁，靠 version 冲突挡后到的那次写入。
                    // 用类名判断而不是直接依赖具体异常类型，避免绑定 Halo 内部实现。
                    .onErrorMap(MemberWithdrawReviewServiceImpl::isOptimisticLockFailure,
                        error -> new AlreadyReviewedException(memberName))
                    .doOnNext(updated -> memberFinder.evictCache())
                    .flatMap(updated -> notifyMember(updated, email, displayName, approved,
                        statusBefore, withdrawReason))
                    .thenReturn(new ReviewResult(approved, statusBefore, approved
                        ? "撤回申请已批准，成员已下架"
                        : "撤回申请已拒绝，成员状态已恢复为" + statusBefore));
            });
    }

    private static boolean isOptimisticLockFailure(Throwable error) {
        return error.getClass().getSimpleName().contains("OptimisticLockingFailure");
    }

    private Mono<Void> notifyMember(Member member, String email, String displayName,
        boolean approved, String statusBefore, String withdrawReason) {
        if (StringUtils.isEmpty(email)) {
            return Mono.empty();
        }
        return settingConfigMember.getBasicConfig()
            .filter(config -> config.isSendEmail())
            .flatMap(config -> {
                String url = externalLinkProcessor.processLink("/members");
                var subject = Reason.Subject.builder()
                    .apiVersion(member.getApiVersion())
                    .kind(member.getKind())
                    .name(member.getMetadata().getName())
                    .title(displayName)
                    .url(url)
                    .build();

                Map<String, Object> attrs = new HashMap<>();
                attrs.put("adminEmail", StringUtils.defaultString(config.getAdminEmail()));
                attrs.put("email", email);
                attrs.put("displayName", displayName);
                attrs.put("reviewResult", approved ? "APPROVED" : "REJECTED");
                attrs.put("reviewStatus", approved ? "已通过" : "已拒绝");
                attrs.put("status", approved ? "已下架" : statusBefore);
                attrs.put("withdrawReason", withdrawReason);
                attrs.put("reviewUrl", url);

                // 注册订阅：让 plugin-mail-template 能匹配到这个 reason
                var interestReason = new Subscription.InterestReason();
                interestReason.setReasonType(USER_MEMBER_WITHDRAW_REVIEW);
                interestReason.setExpression(
                    "props.email == '%s'".formatted(email.replace("'", "''")));
                var subscriber = new Subscription.Subscriber();
                subscriber.setName(UserIdentity.anonymousWithEmail(email).name());

                return notificationCenter.subscribe(subscriber, interestReason)
                    .then(notificationReasonEmitter.emit(USER_MEMBER_WITHDRAW_REVIEW,
                        builder -> builder.attributes(attrs)
                            .author(UserIdentity.anonymousWithEmail(email))
                            .subject(subject)));
            })
            .then();
    }
}
