package run.halo.members.endpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import run.halo.app.core.extension.notification.Reason;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.app.plugin.ApiVersion;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.members.Member;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.finders.impl.MemberFinderImpl;
import static run.halo.members.MemberConstant.*;

/**
 * 管理员撤回审核端点
 */
@RestController
@RequiredArgsConstructor
@ApiVersion("console.api.member.plugin.halo.run/v1alpha1")
@RequestMapping("/members")
public class MemberWithdrawAdminEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExtensionClient client;
    private final SettingConfigMember settingConfigMember;
    private final MemberFinderImpl memberFinder;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;

    /**
     * 获取撤回申请列表
     */
    @GetMapping("/-/withdraw-requests")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getWithdrawRequests() {
        return Mono.fromCallable(() -> {
            List<Member> allMembers = client.listAll(Member.class, null, null);
            if (allMembers == null) {
                return ResponseEntity.ok(Collections.<Map<String, Object>>emptyList());
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Member member : allMembers) {
                if (!"WITHDRAW_REQUESTED".equals(member.getSpec().getStatus())) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("metadata", member.getMetadata());
                item.put("spec", member.getSpec());
                if (member.getMetadata().getAnnotations() != null) {
                    item.put("withdrawReason", member.getMetadata().getAnnotations().get("member.plugin.halo.run/withdraw-reason"));
                    item.put("withdrawEmail", member.getMetadata().getAnnotations().get("member.plugin.halo.run/withdraw-email"));
                    item.put("statusBefore", member.getMetadata().getAnnotations().get("member.plugin.halo.run/status-before-withdraw"));
                }
                result.add(item);
            }
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 批准撤回申请
     */
    @PostMapping("/-/withdraw-approve/{memberName}")
    public Mono<ResponseEntity<Map<String, Object>>> approveWithdraw(@PathVariable String memberName) {
        return Mono.fromCallable(() -> {
            Optional<Member> opt = client.fetch(Member.class, memberName);
            return opt.<ResponseEntity<Map<String, Object>>>map(
                member -> doApproveReject(member, true, "APPROVED", "已通过"))
                .orElseGet(() -> ResponseEntity.notFound().build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 拒绝撤回申请
     */
    @PostMapping("/-/withdraw-reject/{memberName}")
    public Mono<ResponseEntity<Map<String, Object>>> rejectWithdraw(@PathVariable String memberName) {
        return Mono.fromCallable(() -> {
            Optional<Member> opt = client.fetch(Member.class, memberName);
            return opt.<ResponseEntity<Map<String, Object>>>map(
                member -> doApproveReject(member, false, "REJECTED", "已拒绝"))
                .orElseGet(() -> ResponseEntity.notFound().build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Map<String, Object>> doApproveReject(Member member, boolean approved, 
            String reviewResult, String reviewStatus) {
        String statusBefore = "PENDING";
        Map<String, String> annotations = MetadataUtil.nullSafeAnnotations(member);
        if (annotations.containsKey("member.plugin.halo.run/status-before-withdraw")) {
            statusBefore = annotations.get("member.plugin.halo.run/status-before-withdraw");
        }
        
        String email = member.getSpec().getEmail();
        String displayName = member.getSpec().getDisplayName();
        String withdrawReason = annotations.getOrDefault("member.plugin.halo.run/withdraw-reason", "");
        String adminEmail = settingConfigMember.getBasicConfig().block().getAdminEmail();

        // 恢复原始状态
        member.getSpec().setStatus(statusBefore);
        annotations.remove("member.plugin.halo.run/withdraw-review-action");
        annotations.remove("member.plugin.halo.run/withdraw-reviewed-at");
        annotations.remove("member.plugin.halo.run/status-before-withdraw");
        annotations.remove("member.plugin.halo.run/withdraw-email");
        annotations.remove("member.plugin.halo.run/withdraw-reason");
        
        client.update(member);
        memberFinder.evictCache();

        // 发送通知给用户
        publishWithdrawReviewNotice(member, email, displayName, adminEmail, reviewResult, reviewStatus, statusBefore, withdrawReason);

        String action = approved ? "批准" : "拒绝";
        return ResponseEntity.ok(Map.of(
            "failed", false,
            "message", "撤回申请已" + action + "，成员状态已恢复为" + statusBefore
        ));
    }

    /**
     * 发布撤回审核结果通知给用户
     */
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
}
