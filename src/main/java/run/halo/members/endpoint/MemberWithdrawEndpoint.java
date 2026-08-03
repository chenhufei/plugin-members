package run.halo.members.endpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

import run.halo.app.core.extension.notification.Reason;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.app.plugin.ApiVersion;
import run.halo.members.Member;
import run.halo.members.cache.VerificationCodeService;
import run.halo.members.security.RateLimitService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.finders.impl.MemberFinderImpl;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static run.halo.members.MemberConstant.*;

/**
 * 用户自助撤回申请端点
 */
@RestController
@RequiredArgsConstructor
@ApiVersion("api.plugin.halo.run/v1alpha1")
@RequestMapping("/plugins/PluginMembers/membersubmits")
public class MemberWithdrawEndpoint {

    private final ExtensionClient client;
    private final VerificationCodeService codeService;
    private final SettingConfigMember settingConfigMember;
    private final MemberFinderImpl memberFinder;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final RateLimitService rateLimitService;

    /**
     * 发送验证码
     */
    @PostMapping("/-/send-verification-code")
    public Mono<ResponseEntity<Map<String, Object>>> sendVerificationCode(
            @RequestBody WithdrawRequest request) {

        if (StringUtils.isEmpty(request.email) || StringUtils.isEmpty(request.qq)) {
            return Mono.just(errorResponse("请提供QQ号和邮箱"));
        }
        String email = request.email.trim().toLowerCase();
        String qq = request.qq.trim();
        if (!rateLimitService.isRequestAllowed("withdraw-code:" + email + ":" + qq,
            3, Duration.ofMinutes(10))) {
            return Mono.just(errorResponse("验证码发送过于频繁，请稍后再试"));
        }

        return Mono.fromCallable(() -> {
            var config = settingConfigMember.getBasicConfig().block();
            if (config == null || !config.isSendEmail()) {
                return errorResponse("邮件通知未开启，暂时无法发送撤回验证码");
            }
            List<Member> members = client.listAll(Member.class,
                new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
            Optional<Member> found = members.stream()
                .filter(m -> qq.equals(m.getSpec().getQq())
                    && StringUtils.equalsIgnoreCase(email, m.getSpec().getEmail()))
                .findFirst();

            if (found.isEmpty()) {
                return errorResponse("未找到匹配的成员记录");
            }

            Member member = found.get();
            String status = member.getSpec().getStatus();
            if (!"APPROVED".equals(status)
                && !"PENDING".equals(status)
                && !"WITHDRAW_REQUESTED".equals(status)) {
                return errorResponse("该成员暂无申请记录，无法撤回");
            }

            String code = codeService.generateCode(email);
            publishVerificationCode(member, email, code);
            return successResponse("验证码已发送，请查收邮箱");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 提交撤回请求
     */
    @PostMapping("/-/withdraw")
    public Mono<ResponseEntity<Map<String, Object>>> withdraw(
            @RequestBody WithdrawRequest request) {

        if (StringUtils.isEmpty(request.email) || StringUtils.isEmpty(request.code)
            || StringUtils.isEmpty(request.qq)) {
            return Mono.just(errorResponse("请提供邮箱、验证码和QQ号"));
        }

        String email = request.email.trim().toLowerCase();
        String code = request.code.trim();
        String qq = request.qq.trim();
        String reason = request.reason;
        return Mono.fromCallable(() -> {
            boolean valid = codeService.verifyCode(email, code);
            if (!valid) {
                return errorResponse("验证码错误，请重新输入");
            }

            List<Member> members = client.listAll(Member.class,
                new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
            Optional<Member> found = members.stream()
                .filter(m -> qq.equals(m.getSpec().getQq())
                    && StringUtils.equalsIgnoreCase(email, m.getSpec().getEmail()))
                .findFirst();

            if (found.isEmpty()) {
                return errorResponse("未找到匹配的成员记录");
            }

            Member member = found.get();
            String statusBefore = member.getSpec().getStatus();
            String userEmail = member.getSpec().getEmail();
            String displayName = member.getSpec().getDisplayName();
            Map<String, String> annotations = MetadataUtil.nullSafeAnnotations(member);
            annotations.put(WITHDRAW_STATUS_BEFORE, statusBefore);
            annotations.put(WITHDRAW_EMAIL, userEmail);
            annotations.put(WITHDRAW_REASON, reason != null ? reason : "");
            annotations.remove(WITHDRAW_REVIEW_ACTION);
            annotations.remove(WITHDRAW_REVIEWED_AT);

            member.getSpec().setStatus("WITHDRAW_REQUESTED");
            client.update(member);
            memberFinder.evictCache();

            SettingConfigMember.BasicConfig config = settingConfigMember.getBasicConfig().block();
            if (config != null && config.isAutoApproveWithdraw()) {
                String adminEmail = config.getAdminEmail();
                String reviewedAt = Instant.now().toString();
                String withdrawReason = StringUtils.defaultIfBlank(reason, "成员主动撤回");
                member.getSpec().setStatus("REJECTED");
                annotations.put(WITHDRAW_REVIEW_ACTION, "APPROVED");
                annotations.put(WITHDRAW_REVIEWED_AT, reviewedAt);
                annotations.put(REVIEW_ACTION, REVIEW_ACTION_WITHDRAW);
                annotations.put(REVIEW_DESCRIPTION, withdrawReason);
                annotations.put(OFFLINE_AT, reviewedAt);
                annotations.put(OFFLINE_REASON, withdrawReason);
                annotations.put(OFFLINE_SOURCE, "SELF_WITHDRAW");
                client.update(member);
                memberFinder.evictCache();
                if (config.isSendEmail()) {
                    publishWithdrawReviewNotice(member, email, displayName, adminEmail,
                        "APPROVED", "已通过", "已下架", reason != null ? reason : "");
                }
                return successResponse("撤回申请已自动通过，成员已下架");
            }

            if (config != null && config.isSendEmail()) {
                publishWithdrawNotice(member, email, statusBefore, reason != null ? reason : "");
                return successResponse("撤回申请已提交，管理员审核后将通过邮件通知您");
            }
            return successResponse("撤回申请已提交，请等待管理员审核");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(String message) {
        Map<String, Object> errMap = new HashMap<>();
        errMap.put("failed", true);
        errMap.put("message", message);
        return ResponseEntity.badRequest().body(errMap);
    }

    private ResponseEntity<Map<String, Object>> successResponse(String message) {
        Map<String, Object> okMap = new HashMap<>();
        okMap.put("failed", false);
        okMap.put("message", message);
        return ResponseEntity.ok(okMap);
    }

    private void publishVerificationCode(Member member, String email, String code) {
        String url = externalLinkProcessor.processLink("/members");
        var subject = Reason.Subject.builder()
            .apiVersion(member.getApiVersion())
            .kind(member.getKind())
            .name(member.getMetadata().getName())
            .title(member.getSpec().getDisplayName())
            .url(url)
            .build();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("email", email);
        attrs.put("displayName", member.getSpec().getDisplayName());
        attrs.put("code", code);
        var interestReason = new run.halo.app.core.extension.notification.Subscription.InterestReason();
        interestReason.setReasonType(USER_MEMBER_WITHDRAW_CODE);
        interestReason.setExpression("props.email == '%s'".formatted(escapeExpressionValue(email)));
        var subscriber = new run.halo.app.core.extension.notification.Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
        notificationReasonEmitter.emit(USER_MEMBER_WITHDRAW_CODE,
            builder -> builder.attributes(attrs)
                .author(UserIdentity.anonymousWithEmail(email))
                .subject(subject)
        ).block();
    }

    private void publishWithdrawNotice(Member member, String email, String statusBefore,
        String withdrawReason) {
        SettingConfigMember.BasicConfig config = settingConfigMember.getBasicConfig().block();
        if (config == null || !config.isSendEmail() || StringUtils.isEmpty(config.getAdminEmail())) {
            return;
        }

        String adminEmail = config.getAdminEmail();
        var spec = member.getSpec();
        
        String url = externalLinkProcessor.processLink("/console/members");
        var reasonSubject = Reason.Subject.builder()
            .apiVersion(member.getApiVersion())
            .kind(member.getKind())
            .name(member.getMetadata().getName())
            .title(spec.getDisplayName())
            .url(url)
            .build();
        
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("adminEmail", adminEmail);
        attrs.put("withdrawEmail", email);
        attrs.put("displayName", spec.getDisplayName());
        attrs.put("qq", spec.getQq());
        attrs.put("statusBefore", statusBefore);
        attrs.put("withdrawReason", withdrawReason);
        attrs.put("reviewUrl", url);
        
        // 注册订阅：让 plugin-mail-template 能匹配到这个 reason
        var interestReason = new run.halo.app.core.extension.notification.Subscription.InterestReason();
        interestReason.setReasonType(ADMIN_MEMBER_WITHDRAW);
        interestReason.setExpression("props.adminEmail == '%s'".formatted(escapeExpressionValue(adminEmail)));
        var subscriber = new run.halo.app.core.extension.notification.Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(adminEmail).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
        
        notificationReasonEmitter.emit(ADMIN_MEMBER_WITHDRAW,
            builder -> builder.attributes(attrs)
                .author(UserIdentity.anonymousWithEmail(adminEmail))
                .subject(reasonSubject)
            ).block();
    }

    private void publishWithdrawReviewNotice(Member member, String email, String displayName, 
            String adminEmail, String reviewResult, String reviewStatus, String statusBefore, String withdrawReason) {
        var config = settingConfigMember.getBasicConfig().block();
        if (config == null || !config.isSendEmail() || StringUtils.isEmpty(email)) {
            return;
        }

        String url = externalLinkProcessor.processLink("/members");
        var reasonSubject = Reason.Subject.builder()
            .apiVersion(member.getApiVersion())
            .kind(member.getKind())
            .name(member.getMetadata().getName())
            .title(displayName)
            .url(url)
            .build();
        
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("adminEmail", adminEmail);
        attrs.put("email", email);
        attrs.put("displayName", displayName);
        attrs.put("reviewResult", reviewResult);
        attrs.put("reviewStatus", reviewStatus);
        attrs.put("status", statusBefore);
        attrs.put("withdrawReason", withdrawReason);
        attrs.put("reviewUrl", url);
        
        // 注册订阅：让 plugin-mail-template 能匹配到这个 reason
        var interestReason = new run.halo.app.core.extension.notification.Subscription.InterestReason();
        interestReason.setReasonType(USER_MEMBER_WITHDRAW_REVIEW);
        interestReason.setExpression("props.email == '%s'".formatted(escapeExpressionValue(email)));
        var subscriber = new run.halo.app.core.extension.notification.Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
        
        notificationReasonEmitter.emit(USER_MEMBER_WITHDRAW_REVIEW,
            builder -> builder.attributes(attrs)
                .author(UserIdentity.anonymousWithEmail(email))
                .subject(reasonSubject)
            ).block();
    }

    private String escapeExpressionValue(String value) {
        return value.replace("'", "''");
    }

    /**
     * 撤回请求
     */
    public record WithdrawRequest(
        String email,
        String code,
        String qq,
        String reason
    ) {}
}
