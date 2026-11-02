package run.halo.members.service.impl;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
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

    private final ReactiveExtensionClient client;
    private final SettingConfigMember settingConfigMember;
    private final MemberFinderImpl memberFinder;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;

    @Override
    public Mono<ReviewResult> review(String memberName, boolean approved) {
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
                    .doOnNext(updated -> memberFinder.evictCache())
                    .flatMap(updated -> notifyMember(updated, email, displayName, approved,
                        statusBefore, withdrawReason))
                    .thenReturn(new ReviewResult(approved, statusBefore, approved
                        ? "撤回申请已批准，成员已下架"
                        : "撤回申请已拒绝，成员状态已恢复为" + statusBefore));
            });
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
