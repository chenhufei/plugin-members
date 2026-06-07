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
import run.halo.members.service.SettingConfigMember.EmailTemplateConfig;
import static run.halo.members.MemberConstant.ADMIN_MEMBER_SUBMIT;
import static run.halo.members.MemberConstant.MARK_AS_NOTIFIED;
import static run.halo.members.MemberConstant.REVIEW_DESCRIPTION;
import static run.halo.members.MemberConstant.REVIEW_MEMBER_SUBMIT;
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

    @Async
    @EventListener(MemberEvent.class)
    public void onMemberSubmitted(MemberEvent event) {
        Member member = event.getMember();
        var basicConfig = settingConfigMember.getBasicConfig().block();
        
        log.info("处理成员提交事件: {}, 成员邮箱: {}", member.getMetadata().getName(), member.getSpec().getEmail());
        
        if (basicConfig != null) {
            boolean sendEmail = basicConfig.isSendEmail();
            log.info("邮件通知配置: sendEmail={}, adminEmail={}", sendEmail, basicConfig.getAdminEmail());
            
            if (sendEmail && StringUtils.isNotEmpty(basicConfig.getAdminEmail())) {
                log.info("发送管理员通知邮件到: {}", basicConfig.getAdminEmail());
                adminMemberSubmitNoticeReasonPublisher.publishReasonBy(member, basicConfig.getAdminEmail());
            } else {
                log.warn("未发送管理员通知邮件: sendEmail={}, adminEmail={}", sendEmail, basicConfig.getAdminEmail());
            }
            
            var status = member.getSpec().getStatus();
            String email = member.getSpec().getEmail();
            if (StringUtils.isNotEmpty(email) && "PENDING".equals(status)) {
                log.info("发送用户通知邮件到: {}, 状态: {}", email, status);
                userMemberSubmitNoticeReasonPublisher.publishReasonBy(member, email);
            } else if (StringUtils.isNotEmpty(email) && isReviewedStatus(status)) {
                tryMarkReviewAsNotified(member.getMetadata().getName())
                    .ifPresent(reviewedMember -> {
                        log.info("发送自动审核结果通知邮件到: {}, 状态: {}", email, status);
                        reviewMemberSubmitNoticeReasonPublisher.publishReasonBy(reviewedMember, email);
                    });
            } else {
                log.warn("未发送用户通知邮件: email={}, status={}", email, status);
            }
        } else {
            log.warn("基础配置为空，无法发送邮件通知");
        }
    }

    @Async
    @EventListener(ReviewMemberEvent.class)
    public void onMemberReviewed(ReviewMemberEvent event) {
        Member member = event.getMember();
        String email = member.getSpec().getEmail();
        
        log.info("处理成员审核事件: {}, 成员邮箱: {}, 状态: {}", 
            member.getMetadata().getName(), email, member.getSpec().getStatus());
        
        if (StringUtils.isEmpty(email)) {
            log.warn("成员邮箱为空，无法发送审核结果通知: {}", member.getMetadata().getName());
            return;
        }

        var markedMember = tryMarkReviewAsNotified(member.getMetadata().getName());
        if (markedMember.isEmpty()) {
            log.info("成员已标记为已通知，跳过: {}", member.getMetadata().getName());
            return;
        }

        log.info("发送审核结果通知邮件到: {}", email);
        reviewMemberSubmitNoticeReasonPublisher.publishReasonBy(markedMember.get(), email);
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
        return status + ":" + Integer.toHexString(reviewDescription.hashCode());
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class AdminMemberSubmitNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;
        private final SettingConfigMember settingConfigMember;

        public void publishReasonBy(Member member, String adminEmail) {
            log.info("发布管理员通知: 成员={}, 管理员邮箱={}", member.getMetadata().getName(), adminEmail);
            String url = externalLinkProcessor.processLink("/console/plugins/PluginMembers");
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
                    var emailTemplates = settingConfigMember.getEmailTemplateConfig().block();
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
                        .customAdminSubmitSubject(emailTemplates != null && StringUtils.isNotBlank(emailTemplates.getAdminSubmitSubject()) ? emailTemplates.getAdminSubmitSubject() : "")
                        .customAdminSubmitBody(emailTemplates != null && StringUtils.isNotBlank(emailTemplates.getAdminSubmitBody()) ? emailTemplates.getAdminSubmitBody() : "")
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(adminEmail))
                        .subject(reasonSubject);
                }).block();
            log.info("管理员通知发布完成");
        }

        @Builder
        record ReasonData(String adminEmail, String email, String displayName, String school,
                          String qq, String groupName, Boolean autoApproved,
                          String reviewUrl, String website, String description,
                          String customAdminSubmitSubject, String customAdminSubmitBody) {
        }
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class UserMemberSubmitNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;
        private final SettingConfigMember settingConfigMember;

        public void publishReasonBy(Member member, String email) {
            log.info("发布用户通知: 成员={}, 用户邮箱={}", member.getMetadata().getName(), email);
            String url = externalLinkProcessor.processLink("/console/plugins/PluginMembers");
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
                    var emailTemplates = settingConfigMember.getEmailTemplateConfig().block();
                    var attributes = ReasonData.builder()
                        .email(email)
                        .displayName(spec.getDisplayName())
                        .customUserSubmitSubject(emailTemplates != null && StringUtils.isNotBlank(emailTemplates.getUserSubmitSubject()) ? emailTemplates.getUserSubmitSubject() : "")
                        .customUserSubmitBody(emailTemplates != null && StringUtils.isNotBlank(emailTemplates.getUserSubmitBody()) ? emailTemplates.getUserSubmitBody() : "")
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(email))
                        .subject(reasonSubject);
                }).block();
            log.info("用户通知发布完成");
        }

        @Builder
        record ReasonData(String email, String displayName,
                          String customUserSubmitSubject, String customUserSubmitBody) {
        }
    }

    @Component
    @RequiredArgsConstructor
    @SuppressWarnings("deprecation")
    static class ReviewMemberSubmitNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;
        private final ExternalLinkProcessor externalLinkProcessor;
        private final SettingConfigMember settingConfigMember;

        public void publishReasonBy(Member member, String email) {
            log.info("发布审核结果通知: 成员={}, 用户邮箱={}, 状态={}", 
                member.getMetadata().getName(), email, member.getSpec().getStatus());
            var annotations = MetadataUtil.nullSafeAnnotations(member);
            String reviewDescription = annotations.get(REVIEW_DESCRIPTION);
            String url = externalLinkProcessor.processLink("/console/plugins/PluginMembers");
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
                    var emailTemplates = settingConfigMember.getEmailTemplateConfig().block();
                    var attributes = ReasonData.builder()
                        .email(email)
                        .displayName(spec.getDisplayName())
                        .reviewDescription(reviewDescription)
                        .approved("APPROVED".equals(spec.getStatus()))
                        .customReviewResultSubject(emailTemplates != null && StringUtils.isNotBlank(emailTemplates.getReviewResultSubject()) ? emailTemplates.getReviewResultSubject() : "")
                        .customReviewResultBody(emailTemplates != null && StringUtils.isNotBlank(emailTemplates.getReviewResultBody()) ? emailTemplates.getReviewResultBody() : "")
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(email))
                        .subject(reasonSubject);
                }).block();
            log.info("审核结果通知发布完成");
        }

        @Builder
        record ReasonData(String email, String displayName, String reviewDescription, Boolean approved,
                          String customReviewResultSubject, String customReviewResultBody) {
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

    /**
     * 简单的模板替换工具
     * 使用 {variableName} 作为占位符
     * 注意：当前模板渲染通过 Halo 的 Thymeleaf 机制处理，此工具类保留备用
     */
    static class TemplateUtils {
        private TemplateUtils() {}

        /**
         * 替换模板中的变量
         * @param template 模板字符串，如 "你好 {name}"
         * @param vars 变量Map
         * @return 替换后的字符串
         */
        static String render(String template, Map<String, Object> vars) {
            if (StringUtils.isBlank(template)) {
                return template;
            }
            if (vars == null || vars.isEmpty()) {
                return template;
            }
            String result = template;
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                String key = "{" + entry.getKey() + "}";
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                result = result.replace(key, value);
            }
            return result;
        }
    }
}