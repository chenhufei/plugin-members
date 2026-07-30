package run.halo.members.endpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import run.halo.members.Member;
import run.halo.members.cache.VerificationCodeService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.finders.impl.MemberFinderImpl;
import static run.halo.members.MemberConstant.*;

/**
 * 用户自助撤回申请端点
 */
@RestController
@RequiredArgsConstructor
public class MemberWithdrawEndpoint {

    private final ExtensionClient client;
    private final VerificationCodeService codeService;
    private final SettingConfigMember settingConfigMember;
    private final MemberFinderImpl memberFinder;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;

    private final java.util.function.Function<Member, String> FIND_MEMBER_BY_QQ_AND_EMAIL = m -> {
        return null;
    };

    /**
     * 发送验证码
     */
    @PostMapping("/-/send-verification-code")
    public ResponseEntity<Map<String, Object>> sendVerificationCode(
            @RequestBody WithdrawRequest request) {

        if (StringUtils.isEmpty(request.email) || StringUtils.isEmpty(request.qq)) {
            return errorResponse("请提供QQ号和邮箱");
        }

        List<Member> members = client.listAll(Member.class, null, null);
        Optional<Member> found = members.stream()
            .filter(m -> request.qq.equals(m.getSpec().getQq()) 
                && StringUtils.equalsIgnoreCase(request.email, m.getSpec().getEmail()))
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

        String email = member.getSpec().getEmail();
        codeService.generateCode(email);
        publishWithdrawNotice(member, email, status);

        return successResponse("验证码已发送，请查收邮箱");
    }

    /**
     * 提交撤回请求
     */
    @PostMapping("/-/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(
            @RequestBody WithdrawRequest request) {

        if (StringUtils.isEmpty(request.email) || StringUtils.isEmpty(request.code)
            || StringUtils.isEmpty(request.qq)) {
            return errorResponse("请提供邮箱、验证码和QQ号");
        }

        String email = request.email;
        String code = request.code;
        String qq = request.qq;
        String reason = request.reason;

        boolean valid = codeService.verifyCode(email, code);
        if (!valid) {
            return errorResponse("验证码错误，请重新输入");
        }

        List<Member> members = client.listAll(Member.class, null, null);
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

        // 保存原始状态到 annotation
        Map<String, String> annotations = MetadataUtil.nullSafeAnnotations(member);
        annotations.put("member.plugin.halo.run/status-before-withdraw", statusBefore);
        annotations.put("member.plugin.halo.run/withdraw-email", userEmail);
        annotations.put("member.plugin.halo.run/withdraw-reason", reason != null ? reason : "");

        // 更新状态为 WITHDRAW_REQUESTED
        member.getSpec().setStatus("WITHDRAW_REQUESTED");
        client.update(member);
        memberFinder.evictCache();

        SettingConfigMember.BasicConfig config = settingConfigMember.getBasicConfig().block();
        if (config != null && config.isAutoApproveWithdraw()) {
            String adminEmail = config.getAdminEmail();
            // 自动审核通过
            member.getSpec().setStatus(statusBefore);
            annotations.remove("member.plugin.halo.run/withdraw-review-action");
            annotations.remove("member.plugin.halo.run/withdraw-reviewed-at");
            annotations.remove("member.plugin.halo.run/status-before-withdraw");
            annotations.remove("member.plugin.halo.run/withdraw-email");
            annotations.remove("member.plugin.halo.run/withdraw-reason");
            client.update(member);
            memberFinder.evictCache();

            publishWithdrawReviewNotice(member, email, displayName, adminEmail, "APPROVED", "已通过", statusBefore, reason != null ? reason : "");

            return successResponse("撤回申请已自动通过，成员状态已恢复为" + statusBefore);
        }

        return successResponse("撤回申请已提交，管理员审核后将通过邮件通知您");
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

    private void publishWithdrawNotice(Member member, String email, String statusBefore) {
        SettingConfigMember.BasicConfig config = settingConfigMember.getBasicConfig().block();
        if (config == null || StringUtils.isEmpty(config.getAdminEmail())) {
            return;
        }

        String adminEmail = config.getAdminEmail();
        var spec = member.getSpec();
        
        String url = externalLinkProcessor.processLink("/members");
        var reasonSubject = Reason.Subject.builder()
            .apiVersion(member.getApiVersion())
            .kind(member.getKind())
            .name(member.getMetadata().getName())
            .title(spec.getDisplayName())
            .url(url)
            .build();
        
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("adminEmail", adminEmail);
        attrs.put("email", email);
        attrs.put("displayName", spec.getDisplayName());
        attrs.put("qq", spec.getQq());
        attrs.put("statusBefore", statusBefore);
        attrs.put("withdrawReason", "");
        attrs.put("reviewUrl", url);
        
        // 注册订阅：让 plugin-mail-template 能匹配到这个 reason
        var interestReason = new run.halo.app.core.extension.notification.Subscription.InterestReason();
        interestReason.setReasonType(ADMIN_MEMBER_WITHDRAW);
        interestReason.setExpression("props.adminEmail == '%s'".formatted(adminEmail));
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
        interestReason.setExpression("props.email == '%s'".formatted(email));
        var subscriber = new run.halo.app.core.extension.notification.Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        notificationCenter.subscribe(subscriber, interestReason).block();
        
        notificationReasonEmitter.emit(USER_MEMBER_WITHDRAW_REVIEW,
            builder -> builder.attributes(attrs)
                .author(UserIdentity.anonymousWithEmail(email))
                .subject(reasonSubject)
            ).block();
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
