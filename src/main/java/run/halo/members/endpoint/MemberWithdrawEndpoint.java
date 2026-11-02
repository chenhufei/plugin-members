package run.halo.members.endpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
import run.halo.members.service.MailDeliveryGuard;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.finders.impl.MemberFinderImpl;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;
import run.halo.members.utils.EmailUtils;
import run.halo.members.utils.RequestUtils;
import static run.halo.members.MemberConstant.*;

/**
 * 用户自助撤回申请端点
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ApiVersion("api.plugin.halo.run/v1alpha1")
@RequestMapping("/plugins/PluginMembers/membersubmits")
public class MemberWithdrawEndpoint {

    /** 撤回原因上限：原因会写入成员注解并进入通知正文 */
    static final int MAX_REASON_LENGTH = 200;

    private final ExtensionClient client;
    private final VerificationCodeService codeService;
    private final SettingConfigMember settingConfigMember;
    private final MailDeliveryGuard mailDeliveryGuard;
    private final MemberFinderImpl memberFinder;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final RateLimitService rateLimitService;

    /**
     * 发送验证码
     */
    @PostMapping("/-/send-verification-code")
    public Mono<ResponseEntity<Object>> sendVerificationCode(
            @RequestBody WithdrawRequest request,
            org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {

        if (StringUtils.isEmpty(request.email) || StringUtils.isEmpty(request.qq)) {
            return Mono.just(problem(HttpStatus.BAD_REQUEST, "invalid-withdraw-request",
                "撤回请求内容无效", "请提供QQ号和邮箱"));
        }
        String email = request.email.trim().toLowerCase();
        String qq = request.qq.trim();
        // 每个 QQ+邮箱 10 分钟 3 次；同一出口 IP 再做一层限制，
        // 否则换 QQ 号就能绕过，邮件通知变成向任意邮箱投递的入口
        if (!rateLimitService.isRequestAllowed("withdraw-code:" + email + ":" + qq,
            3, Duration.ofMinutes(10))) {
            return Mono.just(problem(HttpStatus.TOO_MANY_REQUESTS, "request-not-permitted",
                "请求过于频繁", "验证码发送过于频繁，请稍后再试"));
        }
        String clientIp = RequestUtils.getClientIP(httpRequest);
        if (!rateLimitService.isRequestAllowed("withdraw-code-ip:" + clientIp,
            10, Duration.ofMinutes(10))) {
            return Mono.just(problem(HttpStatus.TOO_MANY_REQUESTS, "request-not-permitted",
                "请求过于频繁", "验证码发送过于频繁，请稍后再试"));
        }

        return mailDeliveryGuard.unavailabilityReason()
            .flatMap(reason -> reason.isPresent()
                ? Mono.just(problem(HttpStatus.CONFLICT, "withdraw-unavailable", "撤回功能不可用",
                    reason.get()))
                : sendVerificationCodeNow(email, qq));
    }

    private Mono<ResponseEntity<Object>> sendVerificationCodeNow(String email, String qq) {
        return Mono.fromCallable(() -> {
            List<Member> members = client.listAll(Member.class,
                new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
            Optional<Member> found = members.stream()
                .filter(m -> qq.equals(m.getSpec().getQq())
                    && StringUtils.equalsIgnoreCase(email, m.getSpec().getEmail()))
                .findFirst();

            if (found.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "member-not-found",
                    "未找到成员", "未找到匹配的成员记录");
            }

            Member member = found.get();
            String status = member.getSpec().getStatus();
            if (!"APPROVED".equals(status)
                && !"PENDING".equals(status)
                && !"WITHDRAW_REQUESTED".equals(status)) {
                return problem(HttpStatus.CONFLICT, "withdraw-not-allowed",
                    "成员状态不允许撤回", "该成员暂无申请记录，无法撤回");
            }

            String key = verificationKey(email, qq);
            // 已存在未失效的验证码时直接复用，不再重发：
            // 否则刷新界面就能反复触发发信，把验证码接口变成向任意邮箱投递的入口
            if (codeService.hasValidCode(key)) {
                return problem(HttpStatus.TOO_MANY_REQUESTS, "verification-code-already-sent",
                    "验证码已发送", "验证码已在有效期内发送过，请查收邮件或稍后重试");
            }
            String code = codeService.generateCode(key);
            try {
                publishVerificationCode(member, email, code);
            } catch (RuntimeException error) {
                // 邮件没提交成功就保留验证码，会让用户在有效期内既收不到邮件也无法重新获取
                codeService.invalidate(verificationKey(email, qq));
                log.error("提交撤回验证码通知失败", error);
                return problem(HttpStatus.BAD_GATEWAY, "verification-code-send-failed",
                    "验证码发送失败", "验证码邮件提交失败，请稍后重试或联系管理员");
            }
            return successResponse(
                "验证码已发送至 " + EmailUtils.mask(email) + "，5 分钟内有效");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 提交撤回请求
     */
    @PostMapping("/-/withdraw")
    public Mono<ResponseEntity<Object>> withdraw(
            @RequestBody WithdrawRequest request) {

        if (StringUtils.isEmpty(request.email) || StringUtils.isEmpty(request.code)
            || StringUtils.isEmpty(request.qq)) {
            return Mono.just(problem(HttpStatus.BAD_REQUEST, "invalid-withdraw-request",
                "撤回请求内容无效", "请提供邮箱、验证码和QQ号"));
        }

        String email = request.email.trim().toLowerCase();
        String code = request.code.trim();
        String qq = request.qq.trim();
        // 撤回原因会落到注解并出现在通知正文里，先夹到 200 字以内，避免超长内容撑爆注解
        String reason = request.reason == null ? "" : request.reason.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            return Mono.just(problem(HttpStatus.BAD_REQUEST, "invalid-withdraw-request",
                "撤回请求内容无效", "撤回原因请控制在 " + MAX_REASON_LENGTH + " 字以内"));
        }
        String verificationKey = verificationKey(email, qq);
        if (!rateLimitService.isRequestAllowed("withdraw-verify:" + verificationKey,
            10, Duration.ofMinutes(10))) {
            return Mono.just(problem(HttpStatus.TOO_MANY_REQUESTS, "request-not-permitted",
                "请求过于频繁", "验证码尝试次数过多，请稍后重新获取"));
        }
        return Mono.fromCallable(() -> {
            boolean valid = codeService.verifyCode(verificationKey, code);
            if (!valid) {
                return problem(HttpStatus.BAD_REQUEST, "invalid-verification-code",
                    "验证码无效", "验证码错误，请重新输入");
            }

            List<Member> members = client.listAll(Member.class,
                new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
            Optional<Member> found = members.stream()
                .filter(m -> qq.equals(m.getSpec().getQq())
                    && StringUtils.equalsIgnoreCase(email, m.getSpec().getEmail()))
                .findFirst();

            if (found.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "member-not-found",
                    "未找到成员", "未找到匹配的成员记录");
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

            if (config != null && (config.isEnableAdminNotification()
                || config.isSendEmail())) {
                publishWithdrawNotice(member, email, statusBefore, reason != null ? reason : "");
            }
            if (config != null && config.isSendEmail()) {
                return successResponse("撤回申请已提交，管理员审核后将通过邮件通知您");
            }
            return successResponse("撤回申请已提交，请等待管理员审核");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Object> problem(HttpStatus status, String typeSuffix, String title,
        String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://halo.run/probs/" + typeSuffix));
        problem.setTitle(title);
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    private ResponseEntity<Object> successResponse(String message) {
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
        if (config == null) {
            return;
        }

        String adminUsername = StringUtils.defaultString(config.getAdminUsername());
        String adminEmail = StringUtils.defaultString(config.getAdminEmail());
        boolean notifyAdmin = config.isEnableAdminNotification()
            && StringUtils.isNotBlank(adminUsername);
        boolean emailAdmin = config.isSendEmail() && StringUtils.isNotBlank(adminEmail);
        if (!notifyAdmin && !emailAdmin) {
            return;
        }
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
        attrs.put("adminUsername", adminUsername);
        attrs.put("adminEmail", adminEmail);
        attrs.put("withdrawEmail", email);
        attrs.put("displayName", spec.getDisplayName());
        attrs.put("qq", spec.getQq());
        attrs.put("statusBefore", statusBefore);
        attrs.put("withdrawReason", withdrawReason);
        attrs.put("reviewUrl", url);
        
        if (notifyAdmin) {
            subscribeNotification(UserIdentity.of(adminUsername).name(), ADMIN_MEMBER_WITHDRAW,
                "adminUsername", adminUsername);
        }
        if (emailAdmin) {
            subscribeNotification(UserIdentity.anonymousWithEmail(adminEmail).name(),
                ADMIN_MEMBER_WITHDRAW, "adminEmail", adminEmail);
        }
        
        notificationReasonEmitter.emit(ADMIN_MEMBER_WITHDRAW,
            builder -> builder.attributes(attrs)
                .author(UserIdentity.anonymousWithEmail(email))
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

    private void subscribeNotification(String subscriberName, String reasonType,
        String propertyName, String value) {
        var interestReason = new run.halo.app.core.extension.notification.Subscription.InterestReason();
        interestReason.setReasonType(reasonType);
        interestReason.setExpression("props.%s == '%s'".formatted(propertyName,
            escapeExpressionValue(value)));
        var subscriber = new run.halo.app.core.extension.notification.Subscription.Subscriber();
        subscriber.setName(subscriberName);
        notificationCenter.subscribe(subscriber, interestReason).block();
    }

    private String verificationKey(String email, String qq) {
        // 与认领验证码分命名空间，避免跨流程复用与互相挡发信
        return "withdraw:" + email + ":" + qq;
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
