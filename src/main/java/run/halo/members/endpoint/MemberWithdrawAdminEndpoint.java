package run.halo.members.endpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.net.URI;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.plugin.ApiVersion;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.members.Member;
import run.halo.members.service.MemberWithdrawReviewService;
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
    private final MemberWithdrawReviewService withdrawReviewService;

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
    public Mono<ResponseEntity<Object>> approveWithdraw(@PathVariable String memberName) {
        return review(memberName, true);
    }

    /**
     * 拒绝撤回申请
     */
    @PostMapping("/-/withdraw-reject/{memberName}")
    public Mono<ResponseEntity<Object>> rejectWithdraw(@PathVariable String memberName) {
        return review(memberName, false);
    }

    private Mono<ResponseEntity<Object>> review(String memberName, boolean approved) {
        return withdrawReviewService.review(memberName, approved)
            .<ResponseEntity<Object>>map(result -> ResponseEntity.ok(Map.of(
                "failed", false,
                "message", result.message())))
            .onErrorResume(MemberWithdrawReviewService.MemberNotFoundException.class,
                error -> Mono.just(memberNotFound(memberName)))
            .onErrorResume(MemberWithdrawReviewService.AlreadyReviewedException.class,
                error -> Mono.just(withdrawAlreadyReviewed()))
            .onErrorResume(MemberWithdrawReviewService.ReviewInProgressException.class,
                error -> Mono.just(withdrawInProgress()));
    }

    private ResponseEntity<Object> withdrawInProgress() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "该成员的另一次审核还在处理中，请稍后刷新列表");
        problem.setType(URI.create("https://halo.run/probs/withdraw-review-in-progress"));
        problem.setTitle("审核处理中");
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    private ResponseEntity<Object> memberNotFound(String memberName) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "成员 " + memberName + " 不存在");
        problem.setType(URI.create("https://halo.run/probs/member-not-found"));
        problem.setTitle("成员不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    private ResponseEntity<Object> withdrawAlreadyReviewed() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "该撤回申请已处理，请刷新列表");
        problem.setType(URI.create("https://halo.run/probs/withdraw-already-reviewed"));
        problem.setTitle("撤回申请已处理");
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }
}
