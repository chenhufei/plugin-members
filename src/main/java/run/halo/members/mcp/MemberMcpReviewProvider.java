package run.halo.members.mcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.members.Member;
import run.halo.members.MemberConstant;
import run.halo.members.MemberGroup;
import run.halo.members.finders.MemberFinder;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MemberBatchService;
import run.halo.members.service.MemberQueryService;
import run.halo.members.service.MemberStatisticsService;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.service.SchoolAffinityService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.utils.AvatarUtils;
import run.halo.members.utils.QqLinkUtils;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/**
 * 成员 MCP 工具 · 审核域：申请与撤回审核、撤回记录查询、待审核批量通过。
 *
 * @since 1.5.0
 */
public class MemberMcpReviewProvider extends AbstractMemberMcpToolProvider {

    private final MemberBatchService memberBatchService;
    private final MemberWithdrawReviewService withdrawReviewService;
    private final MemberQueryService memberQueryService;

    public MemberMcpReviewProvider(RoleService roleService, ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberBatchService memberBatchService,
        MemberWithdrawReviewService withdrawReviewService,
        MemberQueryService memberQueryService
        ) {
        super(roleService, client, memberFinder);
        this.memberBatchService = memberBatchService;
        this.withdrawReviewService = withdrawReviewService;
        this.memberQueryService = memberQueryService;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            reviewMemberSubmissionsTool(),
            reviewMemberWithdrawalsTool(),
            listMemberWithdrawalsTool(),
            autoApprovePendingTool());
    }

    private McpToolDefinition reviewMemberSubmissionsTool() {
        return McpToolDefinition.builder()
            .name("review_member_submissions")
            .title("Review member submissions")
            .description("Approve or reject member applications in bulk. Each name must be an "
                + "existing member; rejected members are kept for audit.")
            .displayTitle("审核成员申请")
            .displayDescription("批量通过或拒绝成员申请，需成员管理权限。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names to review."),
                "approved", booleanProperty("true to approve, false to reject.")),
                List.of("names", "approved")))
            .annotations(McpToolAnnotations.defaults("Review member submissions"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = requireNames(args);
                var approved = requireBoolean(args, "approved");
                return memberBatchService.batchApprove(names, approved)
                    .map(result -> McpToolResult.success(batchContent(result),
                        result.message()));
            }))
            .build();
    }

    private McpToolDefinition reviewMemberWithdrawalsTool() {
        return McpToolDefinition.builder()
            .name("review_member_withdrawals")
            .title("Review member withdrawals")
            .description("Approve or reject pending withdrawal requests. Approving takes the "
                + "member off the public list; rejecting restores the previous status and "
                + "notifies the member. Find candidates with "
                + "list_members status=WITHDRAW_REQUESTED.")
            .displayTitle("审核撤回申请")
            .displayDescription("批量批准或拒绝成员撤回申请，行为与后台撤回审核一致（含邮件通知）。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names whose withdrawal requests "
                    + "should be reviewed."),
                "approved", booleanProperty("true to approve the withdrawal, false to reject.")),
                List.of("names", "approved")))
            .annotations(McpToolAnnotations.defaults("Review member withdrawals"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = requireNames(args);
                var approved = requireBoolean(args, "approved");
                return Flux.fromIterable(names)
                    .concatMap(name -> withdrawReviewService.review(name, approved)
                        .map(result -> reviewItem(name, "REVIEWED", result.message()))
                        .onErrorResume(
                            MemberWithdrawReviewService.MemberNotFoundException.class,
                            error -> Mono.just(reviewItem(name, "NOT_FOUND",
                                error.getMessage())))
                        .onErrorResume(
                            MemberWithdrawReviewService.AlreadyReviewedException.class,
                            error -> Mono.just(reviewItem(name, "ALREADY_REVIEWED",
                                error.getMessage()))))
                    .collectList()
                    .map(items -> {
                        long reviewed = items.stream()
                            .filter(item -> "REVIEWED".equals(item.get("status")))
                            .count();
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("approved", approved);
                        content.put("total", items.size());
                        content.put("reviewed", reviewed);
                        content.put("failed", items.size() - reviewed);
                        content.put("items", items);
                        return McpToolResult.success(content,
                            "%d of %d withdrawal requests reviewed"
                                .formatted(reviewed, items.size()));
                    });
            }))
            .build();
    }

    private McpToolDefinition listMemberWithdrawalsTool() {
        return McpToolDefinition.builder()
            .name("list_member_withdrawals")
            .title("List withdrawal requests")
            .description("List members waiting for a withdrawal decision, including the reason "
                + "the member gave and the status to restore if the request is rejected. Use "
                + "review_member_withdrawals afterwards to decide.")
            .displayTitle("撤回申请列表")
            .displayDescription("列出待处理的撤回申请，含撤回理由与撤回前状态，便于审核前先核对。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "limit", integerProperty("Maximum requests to return. 1-200, default 50.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("List withdrawal requests"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                final int limit = Math.clamp(intArg(args, "limit", 50), 1, 200);
                return client.list(Member.class, member -> member.getSpec() != null
                        && "WITHDRAW_REQUESTED".equals(member.getSpec().getStatus()), null)
                    .collectList()
                    .map(members -> {
                        var items = members.stream().limit(limit)
                            .map(this::withdrawalItem)
                            .toList();
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("pending", members.size());
                        content.put("returned", items.size());
                        content.put("items", items);
                        return McpToolResult.success(content,
                            items.size() + " of " + members.size()
                                + " withdrawal request(s) returned");
                    });
            }))
            .build();
    }

    private Map<String, Object> withdrawalItem(Member member) {
        var spec = member.getSpec();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", member.getMetadata().getName());
        item.put("displayName", spec.getDisplayName());
        item.put("school", spec.getSchool());
        item.put("email", spec.getEmail());
        item.put("qq", spec.getQq());
        item.put("groupName", spec.getGroupName());
        item.put("hasFriendLink", spec.getQqFriendLink() != null
            && !spec.getQqFriendLink().isBlank());
        item.put("reason", annotation(member, MemberConstant.WITHDRAW_REASON));
        item.put("statusBefore", annotation(member, MemberConstant.WITHDRAW_STATUS_BEFORE));
        return item;
    }

    private McpToolDefinition autoApprovePendingTool() {
        return McpToolDefinition.builder()
            .name("auto_approve_pending")
            .title("Auto approve pending members")
            .description("Approve pending member applications that match optional school, "
                + "group and creation-time filters, oldest first. Defaults to a dry run that "
                + "only lists candidates; pass dryRun=false to approve them. Use "
                + "review_member_submissions when the member names are already known.")
            .displayTitle("自动通过待审核成员")
            .displayDescription("按学校、分组与申请时间筛选待审核成员，默认先预览，再批量通过。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "dryRun", booleanProperty("When true (default), only list matching pending "
                    + "members without changing anything."),
                "limit", integerProperty("Maximum applications to approve. 1-100, default 20."),
                "school", stringProperty("Only applications whose school contains this "
                    + "keyword."),
                "groupName", stringProperty("Only applications in this member group."),
                "createdBefore", stringProperty("Only applications created before this instant "
                    + "(ISO 8601, e.g. 2026-01-01T00:00:00Z) or date (2026-01-01).")),
                List.of()))
            .annotations(McpToolAnnotations.defaults("Auto approve pending members"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final boolean dryRun = booleanArg(args, "dryRun", true);
                final int limit = Math.clamp(intArg(args, "limit", 20), 1, 100);
                final String school = stringArg(args, "school");
                final String groupName = stringArg(args, "groupName");
                final Instant before = instantArg(args, "createdBefore");

                var query = new MemberQueryService.MemberQuery(
                    1, MemberQueryService.MAX_SIZE, null, "PENDING", groupName,
                    "createdTime-asc");
                return memberQueryService.listMembers(query).flatMap(result -> {
                    var candidates = result.getItems().stream()
                        .filter(member -> schoolMatches(member, school))
                        .filter(member -> withinRange(member, null, before))
                        .limit(limit)
                        .toList();

                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("dryRun", dryRun);
                    content.put("limit", limit);
                    content.put("pendingScanned", result.getTotal());
                    content.put("matched", candidates.size());
                    content.put("candidates", candidates.stream()
                        .map(this::pendingCandidate)
                        .toList());

                    if (dryRun || candidates.isEmpty()) {
                        String message = dryRun
                            ? "Dry run: %d pending members matched".formatted(candidates.size())
                            : "No pending members matched";
                        return Mono.just(McpToolResult.success(content, message));
                    }

                    var names = candidates.stream()
                        .map(member -> member.getMetadata().getName())
                        .toList();
                    return memberBatchService.batchApprove(names, true).map(batch -> {
                        content.put("result", batchContent(batch));
                        return McpToolResult.success(content, batch.message());
                    });
                });
            }))
            .build();
    }

    private static boolean schoolMatches(MemberVo member, String keyword) {
        if (keyword == null) {
            return true;
        }
        String school = member.getSpec().getSchool();
        return school != null && school.toLowerCase().contains(keyword.toLowerCase());
    }

    private Map<String, Object> pendingCandidate(MemberVo member) {
        var spec = member.getSpec();
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("name", member.getMetadata().getName());
        candidate.put("displayName", spec.getDisplayName());
        candidate.put("school", spec.getSchool());
        candidate.put("groupName", spec.getGroupName());
        candidate.put("origin", spec.getOrigin());
        candidate.put("createdAt", member.getMetadata().getCreationTimestamp());
        return candidate;
    }
}
