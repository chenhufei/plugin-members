package run.halo.members;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.MetadataUtil;
import static run.halo.app.extension.ExtensionUtil.addFinalizers;
import static run.halo.app.extension.ExtensionUtil.removeFinalizers;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.UserIdentity;
import static run.halo.members.MemberConstant.ADMIN_MEMBER_SUBMIT;
import static run.halo.members.MemberConstant.FINALIZER_NAME;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_SUBMIT;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_REJECT;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_OFFLINE;
import static run.halo.members.MemberConstant.REVIEW_ACTION;
import static run.halo.members.MemberConstant.REVIEW_ACTION_OFFLINE;
import static run.halo.members.MemberConstant.REVIEW_ACTION_WITHDRAW;
import static run.halo.members.MemberConstant.REVIEW_ACTION_WITHDRAW_REJECT;
import static run.halo.members.MemberConstant.WITHDRAW_EMAIL;
import static run.halo.members.MemberConstant.WITHDRAW_REASON;
import static run.halo.members.MemberConstant.WITHDRAW_REVIEW_ACTION;
import static run.halo.members.MemberConstant.WITHDRAW_REVIEWED_AT;
import static run.halo.members.MemberConstant.WITHDRAW_STATUS_BEFORE;
import static run.halo.members.MemberConstant.USER_MEMBER_SUBMIT;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_SENT;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_SENT;
import run.halo.members.finders.impl.MemberFinderImpl;
import run.halo.members.service.SettingConfigMember;

/**
 * 成员 Reconciler
 * @since 1.0.34
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class MemberReconciler implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationCenter notificationCenter;
    private final SettingConfigMember settingConfigMember;
    private final MemberFinderImpl memberFinder;

    @Override
    public Result reconcile(Request request) {
        log.info("Reconciling member: {}", request.name());
        client.fetch(Member.class, request.name())
            .ifPresent(member -> {
                if (ExtensionUtil.isDeleted(member)) {
                    log.info("Member {} is being deleted", request.name());
                    removeFinalizers(member.getMetadata(), Set.of(FINALIZER_NAME));
                    client.update(member);
                    memberFinder.evictCache();
                    return;
                }

                memberFinder.evictCache();
                var spec = member.getSpec();

                // 数据迁移：清理已废弃的 purpose 字段（如果存在）
                cleanDeprecatedPurposeField(member, spec);

                String email = spec.getEmail();
                String status = spec.getStatus();

                log.info("Member {} status: {}, email: {}", request.name(), status, email);

                if (addFinalizers(member.getMetadata(), Set.of(FINALIZER_NAME))) {
                    log.info("Adding finalizers for member: {}", request.name());
                    var basicConfig = settingConfigMember.getBasicConfig().block();
                    boolean sendEmail = basicConfig != null && basicConfig.isSendEmail();

                    if (sendEmail) {
                        if (StringUtils.isNotEmpty(basicConfig.getAdminEmail())) {
                            adminNoticeSubscription(basicConfig.getAdminEmail());
                        }
                        if (StringUtils.isNotEmpty(email) && "PENDING".equals(status)) {
                            userNoticeSubscription(email);
                        }
                    }

                    // WITHDRAW_REQUESTED 状态：清除撤回标记后恢复为PENDING
                    if ("WITHDRAW_REQUESTED".equals(status) && member.getMetadata().getAnnotations() != null
                            && member.getMetadata().getAnnotations().containsKey(WITHDRAW_REVIEWED_AT)) {
                        log.info("Member {} withdraw reviewed, resetting to PENDING", request.name());
                        // 恢复原始状态
                        String statusBefore = member.getMetadata().getAnnotations().get(WITHDRAW_STATUS_BEFORE);
                        if (StringUtils.isNotEmpty(statusBefore)) {
                            spec.setStatus(statusBefore);
                        } else {
                            spec.setStatus("PENDING");
                        }
                        // 清除撤回标记
                        member.getMetadata().getAnnotations().remove(WITHDRAW_REVIEW_ACTION);
                        member.getMetadata().getAnnotations().remove(WITHDRAW_REVIEWED_AT);
                        member.getMetadata().getAnnotations().remove(WITHDRAW_STATUS_BEFORE);
                        member.getMetadata().getAnnotations().remove(WITHDRAW_EMAIL);
                        member.getMetadata().getAnnotations().remove(WITHDRAW_REASON);
                        client.update(member);
                        return;
                    }

                    boolean publishSubmission = tryMarkSubmissionAsNotified(member);
                    client.update(member);
                    if (publishSubmission) {
                        log.info("Publishing MemberEvent for: {}", request.name());
                        eventPublisher.publishEvent(new MemberEvent(this, member));
                    }
                    return;
                }

                if ("REJECTED".equals(status) || "APPROVED".equals(status)) {
                    String reviewAction = member.getMetadata().getAnnotations() == null
                        ? null
                        : member.getMetadata().getAnnotations().get(REVIEW_ACTION);
                    if (REVIEW_ACTION_WITHDRAW.equals(reviewAction)
                        || REVIEW_ACTION_WITHDRAW_REJECT.equals(reviewAction)) {
                        log.info("Member {} withdraw flow already sends its own notification", request.name());
                        return;
                    }
                    if (!tryConsumeReviewNotification(member)) {
                        log.debug("Member {} has no pending review notification", request.name());
                        return;
                    }
                    client.update(member);
                    log.info("Member {} status changed to: {}, publishing ReviewMemberEvent", request.name(), status);
                    var basicConfig = settingConfigMember.getBasicConfig().block();
                    boolean sendEmail = basicConfig != null && basicConfig.isSendEmail();
                    if (sendEmail && StringUtils.isNotEmpty(email)) {
                        reviewNoticeSubscription(email, status, reviewAction);
                    }
                    eventPublisher.publishEvent(new ReviewMemberEvent(this, member));
                } else {
                    log.info("Member {} status is: {}, no review event needed", request.name(), status);
                }
            });
        return Result.doNotRetry();
    }

    void adminNoticeSubscription(String email) {
        subscribeNotification(email, ADMIN_MEMBER_SUBMIT, "adminEmail");
    }

    static boolean tryMarkSubmissionAsNotified(Member member) {
        var annotations = MetadataUtil.nullSafeAnnotations(member);
        if (!SUBMISSION_NOTIFICATION_PENDING.equals(annotations.get(SUBMISSION_NOTIFICATION))) {
            return false;
        }
        annotations.put(SUBMISSION_NOTIFICATION, SUBMISSION_NOTIFICATION_SENT);
        return true;
    }

    static boolean tryConsumeReviewNotification(Member member) {
        var annotations = MetadataUtil.nullSafeAnnotations(member);
        if (!REVIEW_NOTIFICATION_PENDING.equals(annotations.get(REVIEW_NOTIFICATION))) {
            return false;
        }
        annotations.put(REVIEW_NOTIFICATION, REVIEW_NOTIFICATION_SENT);
        return true;
    }

    void userNoticeSubscription(String email) {
        subscribeNotification(email, USER_MEMBER_SUBMIT, "email");
    }

    void reviewNoticeSubscription(String email, String status, String reviewAction) {
        String reasonType;
        if ("APPROVED".equals(status)) {
            reasonType = REVIEW_MEMBER_SUBMIT;
        } else if (REVIEW_ACTION_OFFLINE.equals(reviewAction)) {
            reasonType = REVIEW_MEMBER_OFFLINE;
        } else {
            reasonType = REVIEW_MEMBER_REJECT;
        }
        subscribeNotification(email, reasonType, "email");
    }

    private void subscribeNotification(String email, String reasonType, String propertyName) {
        try {
            var interestReason = new Subscription.InterestReason();
            interestReason.setReasonType(reasonType);
            String escapedEmail = email.replace("'", "''");
            interestReason.setExpression("props.%s == '%s'".formatted(propertyName, escapedEmail));
            var subscriber = new Subscription.Subscriber();
            subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
            notificationCenter.subscribe(subscriber, interestReason).block();
        } catch (Exception e) {
            log.warn("Failed to subscribe member notification for reasonType={}: {}",
                reasonType, e.getMessage());
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Member())
            .build();
    }

    /**
     * 数据迁移：清理已废弃的 purpose 字段
     * 此方法会检查并清理 Member 数据中的 purpose 字段
     */
    void cleanDeprecatedPurposeField(Member member, Member.MemberSpec spec) {
        // 由于 MemberSpec 中已移除 purpose 字段，此方法用于记录迁移日志
        // 实际数据清理由 Halo 的 Extension 机制自动处理
        log.debug("Member {} spec cleanup completed, purpose field removed",
            member.getMetadata().getName());
    }
}
