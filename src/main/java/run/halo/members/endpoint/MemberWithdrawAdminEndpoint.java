package run.halo.members.endpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

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
    public Mono<ResponseEntity<Map<String, Object>>> getWithdrawRequests() {
        return Mono.fromCallable(() -> {
            List<Member> allMembers = client.listAll(Member.class,
                new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
            if (allMembers == null) {
                return ResponseEntity.ok(Map.of(
                    "items", Collections.emptyList(),
                    "total", 0
                ));
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Member member : allMembers) {
                Map<String, String> annotations = MetadataUtil.nullSafeAnnotations(member);
                String status = member.getSpec().getStatus();
                boolean pendingWithdraw = "WITHDRAW_REQUESTED".equals(status);
                boolean reviewedWithdraw = StringUtils.isNotBlank(
                    annotations.get(WITHDRAW_REVIEWED_AT));
                boolean hasOfflineRecord = StringUtils.isNotBlank(annotations.get(OFFLINE_AT));
                if (!pendingWithdraw && !reviewedWithdraw && !hasOfflineRecord) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("metadata", member.getMetadata());
                item.put("spec", member.getSpec());

                if (pendingWithdraw || reviewedWithdraw) {
                    item.put("recordType", "SELF_WITHDRAW");
                    item.put("recordStatus", pendingWithdraw
                        ? "PENDING"
                        : annotations.getOrDefault(WITHDRAW_REVIEW_ACTION, "APPROVED"));
                    item.put("reason", annotations.getOrDefault(WITHDRAW_REASON, ""));
                    item.put("email", annotations.getOrDefault(WITHDRAW_EMAIL,
                        StringUtils.defaultString(member.getSpec().getEmail())));
                    item.put("statusBefore",
                        annotations.getOrDefault(WITHDRAW_STATUS_BEFORE, "PENDING"));
                    item.put("recordedAt", pendingWithdraw
                        ? creationTimestamp(member)
                        : annotations.getOrDefault(WITHDRAW_REVIEWED_AT,
                            creationTimestamp(member)));
                } else {
                    item.put("recordType", "ADMIN_OFFLINE");
                    item.put("recordStatus", "APPROVED".equals(status)
                        ? "RESTORED"
                        : "OFFLINE");
                    item.put("reason", annotations.getOrDefault(OFFLINE_REASON,
                        annotations.getOrDefault(REVIEW_DESCRIPTION, "")));
                    item.put("email", StringUtils.defaultString(member.getSpec().getEmail()));
                    item.put("statusBefore", "APPROVED");
                    item.put("recordedAt", annotations.getOrDefault(OFFLINE_AT,
                        creationTimestamp(member)));
                }
                result.add(item);
            }
            result.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault("recordedAt", "")),
                Comparator.reverseOrder()
            ));
            return ResponseEntity.ok(Map.of(
                "items", result,
                "total", result.size()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String creationTimestamp(Member member) {
        return Optional.ofNullable(member.getMetadata().getCreationTimestamp())
            .map(Object::toString)
            .orElse("");
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
        if (!"WITHDRAW_REQUESTED".equals(member.getSpec().getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                "failed", true,
                "message", "该撤回申请已处理，请刷新列表"
            ));
        }
        String statusBefore = "PENDING";
        Map<String, String> annotations = MetadataUtil.nullSafeAnnotations(member);
        if (annotations.containsKey(WITHDRAW_STATUS_BEFORE)) {
            statusBefore = annotations.get(WITHDRAW_STATUS_BEFORE);
        }
        
        String email = member.getSpec().getEmail();
        String displayName = member.getSpec().getDisplayName();
        String withdrawReason = annotations.getOrDefault(WITHDRAW_REASON, "");
        var config = settingConfigMember.getBasicConfig().block();
        String adminEmail = config == null ? "" : StringUtils.defaultString(config.getAdminEmail());
        String reviewedAt = Instant.now().toString();

        annotations.put(WITHDRAW_REVIEW_ACTION, reviewResult);
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
        
        client.update(member);
        memberFinder.evictCache();

        // 发送通知给用户
        if (config != null && config.isSendEmail()) {
            publishWithdrawReviewNotice(member, email, displayName, adminEmail, reviewResult,
                reviewStatus, approved ? "已下架" : statusBefore, withdrawReason);
        }

        String action = approved ? "批准" : "拒绝";
        return ResponseEntity.ok(Map.of(
            "failed", false,
            "message", approved
                ? "撤回申请已批准，成员已下架"
                : "撤回申请已" + action + "，成员状态已恢复为" + statusBefore
        ));
    }

    /**
     * 发布撤回审核结果通知给用户
     */
    private void publishWithdrawReviewNotice(Member member, String email, String displayName, 
            String adminEmail, String reviewResult, String reviewStatus, String statusBefore, String withdrawReason) {
        if (StringUtils.isEmpty(email)) {
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
        interestReason.setExpression("props.email == '%s'".formatted(email.replace("'", "''")));
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
