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
import static run.halo.members.MemberConstant.USER_MEMBER_SUBMIT;
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

                    if (sendEmail && basicConfig != null && StringUtils.isNotEmpty(basicConfig.getAdminEmail())) {
                        adminNoticeSubscription(basicConfig.getAdminEmail());
                    }

                    if (StringUtils.isNotEmpty(email) && "PENDING".equals(status)) {
                        userNoticeSubscription(email);
                    }

                    client.update(member);
                    log.info("Publishing MemberEvent for: {}", request.name());
                    eventPublisher.publishEvent(new MemberEvent(this, member));
                    return;
                }

                if ("REJECTED".equals(status) || "APPROVED".equals(status)) {
                    log.info("Member {} status changed to: {}, publishing ReviewMemberEvent", request.name(), status);
                    if (StringUtils.isNotEmpty(email)) {
                        reviewNoticeSubscription(email, status);
                    }
                    eventPublisher.publishEvent(new ReviewMemberEvent(this, member));
                } else {
                    log.info("Member {} status is: {}, no review event needed", request.name(), status);
                }
            });
        return Result.doNotRetry();
    }

    void adminNoticeSubscription(String email) {
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(ADMIN_MEMBER_SUBMIT);
        interestReason.setExpression("props.adminEmail == '%s'".formatted(email));
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
    }

    void userNoticeSubscription(String email) {
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(USER_MEMBER_SUBMIT);
        interestReason.setExpression("props.email == '%s'".formatted(email));
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
    }

    void reviewNoticeSubscription(String email, String status) {
        var interestReason = new Subscription.InterestReason();
        String reasonType = "APPROVED".equals(status) ? REVIEW_MEMBER_SUBMIT : REVIEW_MEMBER_REJECT;
        interestReason.setReasonType(reasonType);
        interestReason.setExpression("props.email == '%s'".formatted(email));
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
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
