package run.halo.members;

import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.members.service.SettingConfigMember;
import static run.halo.members.MemberConstant.ADMIN_MEMBER_SUBMIT;
import static run.halo.members.MemberConstant.MARK_AS_NOTIFIED;
import static run.halo.members.MemberConstant.REVIEW_DESCRIPTION;
import static run.halo.members.MemberConstant.REVIEW_ACTION;
import static run.halo.members.MemberConstant.REVIEW_ACTION_OFFLINE;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_OFFLINE;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_SUBMIT;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_REJECT;
import static run.halo.members.MemberConstant.USER_MEMBER_SUBMIT;

/**
 * 成员通知发布器
 * @since 1.0.34
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationReasonPublisher {

    private final ExtensionClient client;
    private final SettingConfigMember settingConfigMember;
    private final AdminMemberSubmitNoticeReasonPublisher adminMemberSubmitNoticeReasonPublisher;
    private final UserMemberSubmitNoticeReasonPublisher userMemberSubmitNoticeReasonPublisher;
    private final ReviewMemberSubmitNoticeReasonPublisher reviewMemberSubmitNoticeReasonPublisher;
    private final ReviewMemberRejectNoticeReasonPublisher reviewMemberRejectNoticeReasonPublisher;
    private final ReviewMemberOfflineNoticeReasonPublisher reviewMemberOfflineNoticeReasonPublisher;

    @Async
    @EventListener(MemberEvent.class)
    public void onMemberSubmitted(MemberEvent event) {
        Member member = event.getMember();
        var basicConfig = settingConfigMember.getBasicConfig().blockOptional();

        log.info("处理成员提交事件: {}, 成员邮箱: {}", member.getMetadata().getName(), member.getSpec().getEmail());

        if (basicConfig.isEmpty() || !basicConfig.get().isSendEmail()) {
            log.debug("成员邮件通知未开启，跳过提交事件: {}", member.getMetadata().getName());
            return;
        }

        var config = basicConfig.get();
        if (StringUtils.isNotEmpty(config.getAdminEmail())) {
            adminMemberSubmitNoticeReasonPublisher.publishReasonBy(member, config.getAdminEmail());
        }

        var status = member.getSpec().getStatus();
        String email = member.getSpec().getEmail();
        if (StringUtils.isNotEmpty(email) && "PENDING".equals(status)) {
            userMemberSubmitNoticeReasonPublisher.publishReasonBy(member, email);
        }
    }

    @Async
    @EventListener(ReviewMemberEvent.class)
    public void onMemberReviewed(ReviewMemberEvent event) {
        Member member = event.getMember();
        String email = member.getSpec().getEmail();

        var basicConfig = settingConfigMember.getBasicConfig().blockOptional();
        if (basicConfig.isEmpty() || !basicConfig.get().isSendEmail()) {
            log.debug("成员邮件通知未开启，跳过审核事件: {}", member.getMetadata().getName());
            return;
        }

        if (StringUtils.isEmpty(email)) {
            log.warn("成员邮箱为空，无法发送审核结果通知: {}", member.getMetadata().getName());
            return;
        }

        var markedMember = tryMarkReviewAsNotified(member.getMetadata().getName());
        if (markedMember.isEmpty()) {
            log.info("成员已标记为已通知，跳过: {}", member.getMetadata().getName());
            return;
        }

        String status = member.getSpec().getStatus();
        var annotations = MetadataUtil.nullSafeAnnotations(markedMember.get());
        if ("REJECTED".equals(status)
            && REVIEW_ACTION_OFFLINE.equals(annotations.get(REVIEW_ACTION))) {
            reviewMemberOfflineNoticeReasonPublisher.publishReasonBy(markedMember.get(), email);
        } else if ("REJECTED".equals(status)) {
            reviewMemberRejectNoticeReasonPublisher.publishReasonBy(markedMember.get(), email);
        } else {
            reviewMemberSubmitNoticeReasonPublisher.publishReasonBy(markedMember.get(), email);
        }
    }

    private Optional<Member> tryMarkReviewAsNotified(String name) {
        var memberOpt = client.fetch(Member.class, name);
        if (memberOpt.isEmpty()) {
            return Optional.empty();
        }
        var member = memberOpt.get();
        if (!isReviewedStatus(member.getSpec().getStatus())) {
            return Optional.empty();
        }
        var annotations = MetadataUtil.nullSafeAnnotations(member);
        String marker = reviewNotificationMarker(member);
        if (marker.equals(annotations.get(MARK_AS_NOTIFIED))) {
            return Optional.empty();
        }
        annotations.put(MARK_AS_NOTIFIED, marker);
        client.update(member);
        return Optional.of(member);
    }

    private boolean isReviewedStatus(String status) {
        return "APPROVED".equals(status) || "REJECTED".equals(status);
    }

    private String reviewNotificationMarker(Member member) {
        var annotations = MetadataUtil.nullSafeAnnotations(member);
        String status = StringUtils.defaultString(member.getSpec().getStatus());
        String reviewDescription = StringUtils.defaultString(annotations.get(REVIEW_DESCRIPTION));
        String reviewAction = StringUtils.defaultString(annotations.get(REVIEW_ACTION));
        return status + ":" + reviewAction + ":" + Integer.toHexString(reviewDescription.hashCode());
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class AdminMemberSubmitNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;

        public void publishReasonBy(Member member, String adminEmail) {
            String url = externalLinkProcessor.processLink("/console/members");
            var spec = member.getSpec();
            
            var reasonSubject = Reason.Subject.builder()
                .apiVersion(member.getApiVersion())
                .kind(member.getKind())
                .name(member.getMetadata().getName())
                .title(spec.getDisplayName())
                .url(url)
                .build();
                
            notificationReasonEmitter.emit(ADMIN_MEMBER_SUBMIT,
                builder -> {
                    var attributes = ReasonData.builder()
                        .adminEmail(adminEmail)
                        .email(spec.getEmail())
                        .displayName(spec.getDisplayName())
                        .school(spec.getSchool())
                        .qq(spec.getQq())
                        .groupName(spec.getGroupName())
                        .autoApproved("APPROVED".equals(spec.getStatus()))
                        .reviewUrl(url)
                        .website(spec.getWebsite() != null ? spec.getWebsite() : "")
                        .description(spec.getDescription() != null ? spec.getDescription() : "")
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(adminEmail))
                        .subject(reasonSubject);
                }).block();
        }

        @Builder
        record ReasonData(String adminEmail, String email, String displayName, String school,
                          String qq, String groupName, Boolean autoApproved,
                          String reviewUrl, String website, String description) {
        }
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class UserMemberSubmitNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;

        public void publishReasonBy(Member member, String email) {
            String url = externalLinkProcessor.processLink("/members");
            var spec = member.getSpec();
            
            var reasonSubject = Reason.Subject.builder()
                .apiVersion(member.getApiVersion())
                .kind(member.getKind())
                .name(member.getMetadata().getName())
                .title(spec.getDisplayName())
                .url(url)
                .build();
                
            notificationReasonEmitter.emit(USER_MEMBER_SUBMIT,
                builder -> {
                    var attributes = ReasonData.builder()
                        .email(email)
                        .displayName(spec.getDisplayName())
                        .school(spec.getSchool())
                        .qq(spec.getQq())
                        .groupName(spec.getGroupName())
                        .memberUrl(url)
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(email))
                        .subject(reasonSubject);
                }).block();
        }

        @Builder
        record ReasonData(String email, String displayName, String school, String qq,
                          String groupName, String memberUrl) {
        }
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class ReviewMemberSubmitNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;

        public void publishReasonBy(Member member, String email) {
            var annotations = MetadataUtil.nullSafeAnnotations(member);
            String reviewDescription = annotations.get(REVIEW_DESCRIPTION);
            String url = externalLinkProcessor.processLink("/members");
            var spec = member.getSpec();
            
            var reasonSubject = Reason.Subject.builder()
                .apiVersion(member.getApiVersion())
                .kind(member.getKind())
                .name(member.getMetadata().getName())
                .title(spec.getDisplayName())
                .url(url)
                .build();
                
            notificationReasonEmitter.emit(REVIEW_MEMBER_SUBMIT,
                builder -> {
                    var attributes = ReasonData.builder()
                        .email(email)
                        .displayName(spec.getDisplayName())
                        .school(spec.getSchool())
                        .qq(spec.getQq())
                        .groupName(spec.getGroupName())
                        .reviewDescription(reviewDescription)
                        .approved("APPROVED".equals(spec.getStatus()))
                        .memberUrl(url)
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(email))
                        .subject(reasonSubject);
                }).block();
        }

        @Builder
        record ReasonData(String email, String displayName, String school, String qq, String groupName,
                          String reviewDescription, Boolean approved, String memberUrl) {
        }
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class ReviewMemberRejectNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;

        public void publishReasonBy(Member member, String email) {
            var annotations = MetadataUtil.nullSafeAnnotations(member);
            String reviewDescription = annotations.get(REVIEW_DESCRIPTION);
            String url = externalLinkProcessor.processLink("/members");
            var spec = member.getSpec();
            
            var reasonSubject = Reason.Subject.builder()
                .apiVersion(member.getApiVersion())
                .kind(member.getKind())
                .name(member.getMetadata().getName())
                .title(spec.getDisplayName())
                .url(url)
                .build();
                
            notificationReasonEmitter.emit(REVIEW_MEMBER_REJECT,
                builder -> {
                    var attributes = ReasonData.builder()
                        .email(email)
                        .displayName(spec.getDisplayName())
                        .school(spec.getSchool())
                        .qq(spec.getQq())
                        .groupName(spec.getGroupName())
                        .reviewDescription(reviewDescription != null ? reviewDescription : "无")
                        .memberUrl(url)
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(email))
                        .subject(reasonSubject);
                }).block();
        }

        @Builder
        record ReasonData(String email, String displayName, String school, String qq, String groupName,
                          String reviewDescription, String memberUrl) {
        }
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class ReviewMemberOfflineNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;

        public void publishReasonBy(Member member, String email) {
            var annotations = MetadataUtil.nullSafeAnnotations(member);
            String offlineReason = StringUtils.defaultIfBlank(
                annotations.get(REVIEW_DESCRIPTION), "管理员未填写下架原因");
            String url = externalLinkProcessor.processLink("/members");
            var spec = member.getSpec();

            var reasonSubject = Reason.Subject.builder()
                .apiVersion(member.getApiVersion())
                .kind(member.getKind())
                .name(member.getMetadata().getName())
                .title(spec.getDisplayName())
                .url(url)
                .build();

            notificationReasonEmitter.emit(REVIEW_MEMBER_OFFLINE,
                builder -> {
                    var attributes = ReasonData.builder()
                        .email(email)
                        .displayName(spec.getDisplayName())
                        .school(spec.getSchool())
                        .qq(spec.getQq())
                        .groupName(spec.getGroupName())
                        .offlineReason(offlineReason)
                        .memberUrl(url)
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(email))
                        .subject(reasonSubject);
                }).block();
        }

        @Builder
        record ReasonData(String email, String displayName, String school, String qq, String groupName,
                          String offlineReason, String memberUrl) {
        }
    }

    static final class ReasonDataConverter {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private ReasonDataConverter() {
        }

        public static <T> Map<String, Object> toAttributeMap(T data) {
            Assert.notNull(data, "Reason attributes must not be null");
            return MAPPER.convertValue(data, new TypeReference<>() {
            });
        }
    }
}
