package run.halo.members.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.members.Member;
import run.halo.members.MemberConstant;
import run.halo.members.finders.MemberFinder;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MemberBatchService;
import run.halo.members.service.MemberQueryService;
import run.halo.members.service.MemberStatisticsService;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/**
 * 成员管理 MCP 工具：成员查询、分组查询、统计、导入导出、申请审核、撤回审核与资料修改。
 *
 * <p>工具只做参数解析与服务编排，业务规则仍由插件既有服务承担；
 * 删除与合并等破坏性操作刻意不暴露给 Agent。
 *
 * @since 1.5.0
 */
public class MemberMcpToolProvider implements McpToolProvider {

    static final String VIEW_ROLE = "role-template-member-view";
    static final String MANAGE_ROLE = "role-template-member-manage";

    private static final String CATEGORY_QUERY = "成员查询";
    private static final String CATEGORY_REVIEW = "成员审核";
    private static final String GROUP_CATEGORY = "成员分组";
    private static final String CATEGORY_CLAIM = "成员认领";

    private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper();

    private static final Set<String> MEMBER_STATUSES =
        Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAW_REQUESTED");

    private final RoleService roleService;
    private final ReactiveExtensionClient client;
    private final MemberFinder memberFinder;
    private final MemberQueryService memberQueryService;
    private final MemberBatchService memberBatchService;
    private final MemberStatisticsService statisticsService;
    private final MemberWithdrawReviewService withdrawReviewService;
    private final ClaimTokenService claimTokenService;
    private final ExternalLinkProcessor externalLinkProcessor;

    public MemberMcpToolProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService,
        MemberStatisticsService statisticsService,
        MemberWithdrawReviewService withdrawReviewService,
        ClaimTokenService claimTokenService,
        ExternalLinkProcessor externalLinkProcessor) {
        this.roleService = roleService;
        this.client = client;
        this.memberFinder = memberFinder;
        this.memberQueryService = memberQueryService;
        this.memberBatchService = memberBatchService;
        this.statisticsService = statisticsService;
        this.withdrawReviewService = withdrawReviewService;
        this.claimTokenService = claimTokenService;
        this.externalLinkProcessor = externalLinkProcessor;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            listMembersTool(),
            getMemberTool(),
            listMemberGroupsTool(),
            getMemberStatisticsTool(),
            reviewMemberSubmissionsTool(),
            reviewMemberWithdrawalsTool(),
            assignMemberGroupTool(),
            updateMemberTool(),
            exportMemberDataTool(),
            batchImportMembersTool(),
            searchMembersAdvancedTool(),
            generateMemberReportTool(),
            memberDuplicateCheckTool(),
            autoApprovePendingTool(),
            autoAssignGroupTool(),
            generateClaimTokensTool());
    }

    private McpToolDefinition listMembersTool() {
        return McpToolDefinition.builder()
            .name("list_members")
            .title("List members")
            .description("List alliance members with optional filters. Use status=PENDING to "
                + "review member applications and status=WITHDRAW_REQUESTED for withdrawal "
                + "requests. Returns one page of members.")
            .displayTitle("成员列表")
            .displayDescription("按状态、分组和关键词分页查询成员，可用于查看待审核申请。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "page", integerProperty("Page number, starting from 1. Default 1."),
                "size", integerProperty("Page size, 1-100. Default 20."),
                "status", enumProperty(List.copyOf(MEMBER_STATUSES),
                    "Member status filter."),
                "groupName", stringProperty("Member group name filter."),
                "keyword", stringProperty("Keyword matched against display name, email, "
                    + "school and QQ."),
                "sort", enumProperty(List.of("createdTime-desc", "createdTime-asc",
                    "name-asc", "name-desc"), "Sort order.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("List members"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var page = intArg(args, "page", 1);
                var size = Math.clamp(intArg(args, "size", 20), 1, 100);
                var query = new MemberQueryService.MemberQuery(
                    page,
                    size,
                    stringArg(args, "keyword"),
                    stringArg(args, "status"),
                    stringArg(args, "groupName"),
                    stringArg(args, "sort"));
                return memberQueryService.listMembers(query)
                    .map(result -> McpToolResult.success(pageContent(result), summary(result)));
            }))
            .build();
    }

    private McpToolDefinition getMemberTool() {
        return McpToolDefinition.builder()
            .name("get_member")
            .title("Get member")
            .description("Read one member by its account name, including review notes and "
                + "withdrawal state.")
            .displayTitle("成员详情")
            .displayDescription("按账号名称读取单个成员的资料与审核状态。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Member account name (metadata.name).")),
                List.of("name")))
            .annotations(McpToolAnnotations.readOnly("Get member"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var name = requireString(args, "name");
                return client.fetch(Member.class, name)
                    .map(this::memberDetail)
                    .<McpToolResult>map(McpToolResult::success)
                    .switchIfEmpty(Mono.fromSupplier(() -> McpToolResult.error("NOT_FOUND",
                        "Member " + name + " was not found.")));
            }))
            .build();
    }

    private McpToolDefinition listMemberGroupsTool() {
        return McpToolDefinition.builder()
            .name("list_member_groups")
            .title("List member groups")
            .description("List all member groups used to organize published members.")
            .displayTitle("成员分组列表")
            .displayDescription("读取全部成员分组及其说明。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(), List.of()))
            .annotations(McpToolAnnotations.readOnly("List member groups"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(),
                args -> memberFinder.listAllGroups()
                    .collectList()
                    .map(groups -> {
                        var items = groups.stream().map(this::groupContent).toList();
                        return McpToolResult.success(
                            Map.of("total", items.size(), "items", items));
                    })))
            .build();
    }

    private McpToolDefinition getMemberStatisticsTool() {
        return McpToolDefinition.builder()
            .name("get_member_statistics")
            .title("Get member statistics")
            .description("Read member totals, approval rate and distribution by group, status "
                + "and school.")
            .displayTitle("成员统计")
            .displayDescription("读取成员总量、通过率与分组、状态、学校分布。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(), List.of()))
            .annotations(McpToolAnnotations.readOnly("Get member statistics"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var overall = statisticsService.getOverallStatistics();
                var byGroup = statisticsService.getGroupStatistics();
                var byStatus = statisticsService.getStatusStatistics();
                var bySchool = statisticsService.getSchoolStatistics();
                return Mono.zip(overall, byGroup, byStatus, bySchool)
                    .map(tuple -> {
                        var stats = tuple.getT1();
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("totalMembers", stats.totalMembers());
                        content.put("approvedMembers", stats.approvedMembers());
                        content.put("pendingMembers", stats.pendingMembers());
                        content.put("rejectedMembers", stats.rejectedMembers());
                        content.put("totalGroups", stats.totalGroups());
                        content.put("approvalRate", stats.approvalRate());
                        content.put("mostPopularGroup", stats.mostPopularGroup());
                        content.put("mostPopularSchool", stats.mostPopularSchool());
                        content.put("byGroup", tuple.getT2());
                        content.put("byStatus", tuple.getT3());
                        content.put("bySchool", tuple.getT4());
                        return McpToolResult.success(content);
                    });
            }))
            .build();
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

    private static Map<String, Object> reviewItem(String name, String status, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        item.put("message", message);
        return item;
    }

    private McpToolDefinition assignMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("assign_member_group")
            .title("Assign member group")
            .description("Move members into an existing member group in bulk.")
            .displayTitle("调整成员分组")
            .displayDescription("批量把成员移动到指定分组，需成员管理权限。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names to move."),
                "groupName", stringProperty("Target member group name.")),
                List.of("names", "groupName")))
            .annotations(McpToolAnnotations.defaults("Assign member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = requireNames(args);
                var groupName = requireString(args, "groupName");
                return memberBatchService.batchChangeGroup(names, groupName)
                    .map(result -> McpToolResult.success(batchContent(result),
                        result.message()));
            }))
            .build();
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

    private McpToolDefinition autoAssignGroupTool() {
        return McpToolDefinition.builder()
            .name("auto_assign_group")
            .title("Auto assign member groups")
            .description("Move members into existing groups by school keyword rules. The rules "
                + "object maps a school keyword to a target group name and the first matching "
                + "rule wins. Defaults to a dry run preview; pass dryRun=false to apply. Use "
                + "assign_member_group when the member names are already known.")
            .displayTitle("按学校自动分组")
            .displayDescription("按学校关键词规则批量调整成员分组，默认先预览，再执行移动。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "rules", rulesProperty("Object mapping a school keyword to an existing member "
                    + "group name, e.g. {\"清华\": \"union\"}. First matching rule wins."),
                "onlyUngrouped", booleanProperty("Only move members without a group. "
                    + "Default true."),
                "status", enumProperty(List.copyOf(MEMBER_STATUSES),
                    "Member status filter. Default APPROVED."),
                "dryRun", booleanProperty("When true (default), only list planned "
                    + "assignments without changing anything."),
                "limit", integerProperty("Maximum members to move. 1-200, default 50.")),
                List.of("rules")))
            .annotations(McpToolAnnotations.defaults("Auto assign member groups"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final Map<String, String> rules = requireRules(args);
                final boolean onlyUngrouped = booleanArg(args, "onlyUngrouped", true);
                final String statusArg = stringArg(args, "status");
                final String status = statusArg == null ? "APPROVED" : statusArg;
                final boolean dryRun = booleanArg(args, "dryRun", true);
                final int limit = Math.clamp(intArg(args, "limit", 50), 1, 200);

                return memberFinder.listAllGroups().collectList().flatMap(groups -> {
                    Set<String> knownGroups = groups.stream()
                        .map(group -> group.getMetadata().getName())
                        .collect(Collectors.toSet());
                    var missingGroups = rules.values().stream()
                        .filter(groupName -> !knownGroups.contains(groupName))
                        .distinct()
                        .toList();
                    if (!missingGroups.isEmpty()) {
                        throw invalidArgument("Unknown member group(s): "
                            + String.join(", ", missingGroups)
                            + ". Use list_member_groups to see available groups.");
                    }

                    var query = new MemberQueryService.MemberQuery(
                        1, MemberQueryService.MAX_SIZE, null, status, null,
                        "createdTime-asc");
                    return memberQueryService.listMembers(query).flatMap(result -> {
                        var assignments = new ArrayList<Map<String, Object>>();
                        Map<String, List<String>> byGroup = new LinkedHashMap<>();
                        int matched = 0;
                        for (MemberVo member : result.getItems()) {
                            var spec = member.getSpec();
                            if (onlyUngrouped && spec.getGroupName() != null
                                && !spec.getGroupName().isBlank()) {
                                continue;
                            }
                            String targetGroup = matchGroup(spec.getSchool(), rules);
                            if (targetGroup == null
                                || targetGroup.equals(spec.getGroupName())) {
                                continue;
                            }
                            matched++;
                            if (assignments.size() >= limit) {
                                continue;
                            }
                            Map<String, Object> assignment = new LinkedHashMap<>();
                            assignment.put("name", member.getMetadata().getName());
                            assignment.put("displayName", spec.getDisplayName());
                            assignment.put("school", spec.getSchool());
                            assignment.put("fromGroup", spec.getGroupName());
                            assignment.put("toGroup", targetGroup);
                            assignments.add(assignment);
                            byGroup.computeIfAbsent(targetGroup, key -> new ArrayList<>())
                                .add(member.getMetadata().getName());
                        }

                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("dryRun", dryRun);
                        content.put("status", status);
                        content.put("onlyUngrouped", onlyUngrouped);
                        content.put("limit", limit);
                        content.put("scanned", result.getTotal());
                        content.put("matched", matched);
                        content.put("planned", assignments.size());
                        content.put("assignments", assignments);

                        if (dryRun || byGroup.isEmpty()) {
                            String message = dryRun
                                ? "Dry run: %d members would be assigned".formatted(
                                    assignments.size())
                                : "No members matched the group rules";
                            return Mono.just(McpToolResult.success(content, message));
                        }

                        return Flux.fromIterable(byGroup.entrySet())
                            .concatMap(entry -> memberBatchService
                                .batchChangeGroup(entry.getValue(), entry.getKey())
                                .map(batch -> {
                                    Map<String, Object> item = batchContent(batch);
                                    item.put("groupName", entry.getKey());
                                    return item;
                                }))
                            .collectList()
                            .map(results -> {
                                content.put("result", results);
                                int moved = results.stream()
                                    .mapToInt(item -> (Integer) item.get("success"))
                                    .sum();
                                return McpToolResult.success(content,
                                    "%d of %d members moved".formatted(moved,
                                        assignments.size()));
                            });
                    });
                });
            }))
            .build();
    }

    private McpToolDefinition generateClaimTokensTool() {
        return McpToolDefinition.builder()
            .name("generate_claim_tokens")
            .title("Generate claim tokens")
            .description("Generate QQ-link claim tokens for members who have a QQ number but "
                + "no qqFriendLink yet. Pass member names explicitly, or use "
                + "autoDetect=true to scan approved members. Returns each member with its "
                + "one-time claim URL, built from the site external URL and pointing at the "
                + "theme /claim page, that the admin can forward to the member.")
            .displayTitle("生成认领链接")
            .displayDescription("为有 QQ 号但缺少 QQ 链接的成员批量生成一次性认领链接。")
            .category(CATEGORY_CLAIM)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names to generate tokens for. "
                    + "Takes precedence over autoDetect."),
                "autoDetect", booleanProperty("When true and names is omitted, scan approved "
                    + "members whose QQ is set but qqFriendLink is empty."),
                "limit", integerProperty("Maximum members to process in one call. 1-200, "
                    + "default 50.")),
                List.of()))
            .annotations(McpToolAnnotations.defaults("Generate claim tokens"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = optionalNames(args, "names");
                final int limit = Math.clamp(intArg(args, "limit", 50), 1, 200);
                final boolean autoDetect = names == null && booleanArg(args, "autoDetect", false);

                if (names == null && !autoDetect) {
                    throw invalidArgument(
                        "Provide 'names' or set autoDetect=true.");
                }

                if (names == null) {
                    var query = new MemberQueryService.MemberQuery(
                        1, MemberQueryService.MAX_SIZE, null, "APPROVED", null, null);
                    return memberQueryService.listMembers(query).flatMap(result -> {
                        var candidates = result.getItems().stream()
                            .filter(m -> {
                                var spec = m.getSpec();
                                return spec.getQq() != null && !spec.getQq().isBlank()
                                    && (spec.getQqFriendLink() == null
                                        || spec.getQqFriendLink().isBlank());
                            })
                            .limit(limit)
                            .map(m -> m.getMetadata().getName())
                            .toList();
                        return generateTokensForNames(candidates);
                    });
                }

                return generateTokensForNames(names.subList(0, Math.min(names.size(), limit)));
            }))
            .build();
    }

    private String buildClaimUrl(String token) {
        return externalLinkProcessor.processLink("/claim?token=" + token);
    }

    private Mono<McpToolResult> generateTokensForNames(List<String> names) {
        if (names.isEmpty()) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("generated", 0);
            content.put("items", List.of());
            return Mono.just(McpToolResult.success(content, "No members to generate tokens for"));
        }
        return Flux.fromIterable(names)
            .concatMap(name -> claimTokenService.generateToken(name, null)
                .map(token -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("token", token);
                    item.put("claimUrl", buildClaimUrl(token));
                    return item;
                })
                .onErrorResume(error -> Mono.just(Map.of(
                    "name", name,
                    "error", error.getMessage() != null ? error.getMessage() : "unknown"))))
            .collectList()
            .map(items -> {
                long generated = items.stream().filter(i -> !i.containsKey("error")).count();
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("generated", generated);
                content.put("failed", items.size() - generated);
                content.put("items", items);
                return McpToolResult.success(content,
                    "%d claim token(s) generated".formatted(generated));
            });
    }

    private static Map<String, String> requireRules(Map<String, Object> args) {
        if (!(args.get("rules") instanceof Map<?, ?> raw) || raw.isEmpty()) {
            throw invalidArgument("Argument 'rules' must be a non-empty object mapping a "
                + "school keyword to a group name.");
        }
        Map<String, String> rules = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String keyword = key == null ? null : String.valueOf(key).trim();
            String groupName = value == null ? null : String.valueOf(value).trim();
            if (keyword == null || keyword.isEmpty() || groupName == null
                || groupName.isEmpty()) {
                throw invalidArgument("Each rule needs a school keyword and a group name.");
            }
            rules.put(keyword, groupName);
        });
        return rules;
    }

    private static String matchGroup(String school, Map<String, String> rules) {
        if (school == null || school.isBlank()) {
            return null;
        }
        String haystack = school.toLowerCase();
        for (var entry : rules.entrySet()) {
            if (haystack.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Object> rulesProperty(String description) {
        Map<String, Object> schema = property("object", description);
        schema.put("additionalProperties", property("string", null));
        return schema;
    }

    private McpToolDefinition updateMemberTool() {
        return McpToolDefinition.builder()
            .name("update_member")
            .title("Update member profile")
            .description("Update a member's display name, email, school, QQ or group. Omitted "
                + "fields stay unchanged; approved members are reflected on the public "
                + "members page immediately.")
            .displayTitle("修改成员资料")
            .displayDescription("修改成员昵称、邮箱、学校、QQ 或分组，未提供的字段保持不变。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Member account name (metadata.name)."),
                "displayName", stringProperty("New display name."),
                "email", stringProperty("New email address."),
                "school", stringProperty("New school name."),
                "qq", stringProperty("New QQ number."),
                "groupName", stringProperty("Move the member to this group.")),
                List.of("name")))
            .annotations(McpToolAnnotations.defaults("Update member profile"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var name = requireString(args, "name");
                var displayName = stringArg(args, "displayName");
                var email = stringArg(args, "email");
                var school = stringArg(args, "school");
                var qq = stringArg(args, "qq");
                var groupName = stringArg(args, "groupName");
                if (displayName == null && email == null && school == null && qq == null
                    && groupName == null) {
                    throw invalidArgument("Provide at least one field to update.");
                }
                return client.fetch(Member.class, name)
                    .flatMap(member -> {
                        var spec = member.getSpec();
                        if (displayName != null) {
                            spec.setDisplayName(displayName);
                        }
                        if (email != null) {
                            spec.setEmail(email);
                        }
                        if (school != null) {
                            spec.setSchool(school);
                        }
                        if (qq != null) {
                            spec.setQq(qq);
                        }
                        if (groupName != null) {
                            spec.setGroupName(groupName);
                        }
                        return client.update(member);
                    })
                    .map(updated -> McpToolResult.success(memberDetail(updated),
                        "Member " + name + " updated"))
                    .switchIfEmpty(Mono.fromSupplier(() -> McpToolResult.error("NOT_FOUND",
                        "Member " + name + " was not found.")));
            }))
            .build();
    }
    
    private McpToolDefinition exportMemberDataTool() {
        return McpToolDefinition.builder()
            .name("export_member_data")
            .title("Export member data")
            .description("Export member data to CSV or JSON. Export an explicit name list, or "
                + "export every member matching status and group filters.")
            .displayTitle("导出成员数据")
            .displayDescription("导出成员数据为 CSV 或 JSON，可按名称列表或按状态、分组筛选。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "format", enumProperty(List.of("csv", "json"), "Export format. Default csv."),
                "names", stringArrayProperty("Member names to export. Takes precedence over "
                    + "filters."),
                "status", enumProperty(List.copyOf(MEMBER_STATUSES), "Status filter."),
                "groupName", stringProperty("Member group name filter.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Export member data"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var formatArg = stringArg(args, "format");
                final String format = formatArg == null ? "csv" : formatArg;
                var names = optionalNames(args, "names");
                if (names != null && !names.isEmpty()) {
                    return exportMembers(names, format);
                }
                var status = stringArg(args, "status");
                var groupName = stringArg(args, "groupName");
                if (status == null && groupName == null) {
                    return exportMembers(null, format);
                }
                var query = new MemberQueryService.MemberQuery(
                    1, MemberQueryService.MAX_SIZE, null, status, groupName, null);
                return memberQueryService.listMembers(query)
                    .flatMap(result -> exportMembers(
                        result.getItems().stream().map(m -> m.getMetadata().getName()).toList(),
                        format));
            }))
            .build();
    }

    private Mono<McpToolResult> exportMembers(List<String> memberNames, String format) {
        var export = "json".equalsIgnoreCase(format)
            ? memberBatchService.exportToJSON(memberNames)
            : memberBatchService.exportToCSV(memberNames);
        return export.map(data -> McpToolResult.success(
            Map.of("format", format, "data", data),
            "Member data exported as " + format));
    }
    
    private McpToolDefinition batchImportMembersTool() {
        return McpToolDefinition.builder()
            .name("batch_import_members")
            .title("Batch import members")
            .description("Import members from CSV or JSON data. Members will be created with PENDING status.")
            .displayTitle("批量导入成员")
            .displayDescription("从 CSV 或 JSON 数据批量导入成员，新成员状态为待审核。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "format", enumProperty(List.of("csv", "json"), "Import data format."),
                "data", stringProperty("CSV or JSON content to import."),
                "defaultGroup", stringProperty("Default group name if not specified in data.")),
                List.of("format", "data")))
            .annotations(McpToolAnnotations.defaults("Batch import members"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var format = requireString(args, "format");
                var data = requireString(args, "data");
                var defaultGroup = stringArg(args, "defaultGroup");
                
                if ("json".equalsIgnoreCase(format)) {
                    return memberBatchService.batchImportFromJSON(data, defaultGroup)
                        .map(result -> McpToolResult.success(batchContent(result), result.message()));
                } else if ("csv".equalsIgnoreCase(format)) {
                    return memberBatchService.batchImportFromCSV(data, defaultGroup)
                        .map(result -> McpToolResult.success(batchContent(result), result.message()));
                } else {
                    return Mono.just(McpToolResult.error("INVALID_FORMAT",
                        "Format must be 'csv' or 'json'"));
                }
            }))
            .build();
    }
    
    private McpToolDefinition searchMembersAdvancedTool() {
        return McpToolDefinition.builder()
            .name("search_members_advanced")
            .title("Advanced member search")
            .description("Search members with combined filters: keyword, status, group, "
                + "creation date range and optional fuzzy matching. Returns one page.")
            .displayTitle("高级成员搜索")
            .displayDescription("组合关键词、状态、分组、创建时间范围与模糊匹配查询成员。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "page", integerProperty("Page number, starting from 1. Default 1."),
                "size", integerProperty("Page size, 1-100. Default 20."),
                "keyword", stringProperty("Search keyword for display name, email, school "
                    + "or QQ."),
                "status", enumProperty(List.copyOf(MEMBER_STATUSES), "Member status filter."),
                "groupName", stringProperty("Member group name filter."),
                "createdAfter", stringProperty("Only members created after this instant "
                    + "(ISO 8601, e.g. 2026-01-01T00:00:00Z)."),
                "createdBefore", stringProperty("Only members created before this instant "
                    + "(ISO 8601)."),
                "fuzzyMatch", booleanProperty("Treat keyword as a subsequence match instead "
                    + "of a substring match. Default false."),
                "sort", enumProperty(List.of("createdTime-desc", "createdTime-asc",
                    "name-asc", "name-desc"), "Sort order.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Advanced member search"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                final int page = Math.max(1, intArg(args, "page", 1));
                final int size = Math.clamp(intArg(args, "size", 20), 1, 100);
                final var keyword = stringArg(args, "keyword");
                final var status = stringArg(args, "status");
                final var groupName = stringArg(args, "groupName");
                final var sort = stringArg(args, "sort");
                final boolean fuzzy = booleanArg(args, "fuzzyMatch", false);
                final Instant after = instantArg(args, "createdAfter");
                final Instant before = instantArg(args, "createdBefore");

                // Fuzzy and date filtering are evaluated after the base query, so the base
                // query must not pre-filter on keyword or paginate.
                var query = new MemberQueryService.MemberQuery(
                    1, MemberQueryService.MAX_SIZE,
                    fuzzy ? null : keyword, status, groupName, sort);
                return memberQueryService.listMembers(query)
                    .map(result -> {
                        var matched = result.getItems().stream()
                            .filter(member -> !fuzzy || fuzzyKeywordMatch(member, keyword))
                            .filter(member -> withinRange(member, after, before))
                            .toList();
                        int total = matched.size();
                        // page 上限没有约束，(page-1)*size 在 int 下会溢出成负数，
                        // 随后 subList(-1, ...) 直接抛 IndexOutOfBoundsException
                        long offset = (long) (page - 1) * size;
                        int from = (int) Math.min(Math.max(offset, 0L), total);
                        int to = Math.min(from + size, total);
                        List<Map<String, Object>> items = matched.subList(from, to).stream()
                            .map(this::memberSummary)
                            .toList();

                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("page", page);
                        content.put("size", size);
                        content.put("total", total);
                        content.put("items", items);
                        content.put("filters", Map.of(
                            "keyword", keyword == null ? "" : keyword,
                            "status", status == null ? "" : status,
                            "groupName", groupName == null ? "" : groupName,
                            "createdAfter", after == null ? "" : after.toString(),
                            "createdBefore", before == null ? "" : before.toString(),
                            "fuzzyMatch", fuzzy));
                        return McpToolResult.success(content,
                            "%d of %d members on page %d".formatted(items.size(), total, page));
                    });
            }))
            .build();
    }

    private boolean fuzzyKeywordMatch(MemberVo member, String keyword) {
        if (keyword == null) {
            return true;
        }
        var spec = member.getSpec();
        return isSubsequence(spec.getDisplayName(), keyword)
            || isSubsequence(spec.getEmail(), keyword)
            || isSubsequence(spec.getSchool(), keyword)
            || isSubsequence(spec.getQq(), keyword);
    }

    private static boolean isSubsequence(String value, String keyword) {
        if (value == null) {
            return false;
        }
        String haystack = value.toLowerCase();
        String needle = keyword.toLowerCase();
        int index = 0;
        for (int i = 0; i < haystack.length() && index < needle.length(); i++) {
            if (haystack.charAt(i) == needle.charAt(index)) {
                index++;
            }
        }
        return index == needle.length();
    }

    private static boolean withinRange(MemberVo member, Instant after, Instant before) {
        Instant created = member.getMetadata().getCreationTimestamp();
        if (created == null) {
            return after == null && before == null;
        }
        return (after == null || !created.isBefore(after))
            && (before == null || !created.isAfter(before));
    }

    private McpToolDefinition generateMemberReportTool() {
        return McpToolDefinition.builder()
            .name("generate_member_report")
            .title("Generate member report")
            .description("Generate a member statistics report for overall totals, one group, "
                + "status distribution or school distribution. Use format=csv or format=json "
                + "to receive dimension rows, or format=summary for a readable digest.")
            .displayTitle("生成成员报告")
            .displayDescription("生成成员统计报告：总量、分组、状态或学校分布，支持 markdown 摘要与 CSV/JSON 明细。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "type", enumProperty(List.of("overall", "group", "status", "school"),
                    "Report type. Default 'overall'."),
                "groupName", stringProperty("Restrict a group-type report to one group."),
                "trendDays", integerProperty("Include a daily application trend for the last "
                    + "N days (1-90). Omit to skip the trend."),
                "format", enumProperty(List.of("summary", "csv", "json"),
                    "Output format. Default 'summary'.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Generate member report"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var typeArg = stringArg(args, "type");
                final String type = typeArg != null ? typeArg : "overall";
                var formatArg = stringArg(args, "format");
                final String format = formatArg != null ? formatArg : "summary";
                final String groupName = stringArg(args, "groupName");
                final int trendDays = Math.clamp(intArg(args, "trendDays", 0), 0, 90);
                final Mono<java.util.Map<String, Integer>> trend = trendDays > 0
                    ? statisticsService.getApplicationTrend(trendDays)
                    : Mono.just(java.util.Map.of());

                Mono<MemberReport> report = switch (type) {
                    case "group" -> statisticsService.getGroupStatistics()
                        .map(stats -> groupName == null ? stats
                            : java.util.Map.of(groupName,
                                stats.getOrDefault(groupName, 0)))
                        .map(stats -> new MemberReport("group", stats));
                    case "status" -> statisticsService.getStatusStatistics()
                        .map(stats -> new MemberReport("status", stats));
                    case "school" -> statisticsService.getSchoolStatistics()
                        .map(stats -> new MemberReport("school", stats));
                    default -> statisticsService.getOverallStatistics()
                        .map(MemberReport::overall);
                };

                return Mono.zip(report, trend).map(tuple -> {
                    var memberReport = tuple.getT1();
                    var trendData = tuple.getT2();
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("reportType", memberReport.type());
                    content.put("format", format);
                    content.put("totals", memberReport.totals());
                    content.put("rows", memberReport.rows());
                    if (!trendData.isEmpty()) {
                        content.put("trendDays", trendDays);
                        content.put("trend", trendData);
                    }
                    content.put("report", renderReport(memberReport, trendData, format));
                    return McpToolResult.success(content,
                        memberReport.type() + " report rendered as " + format);
                });
            }))
            .build();
    }

    private record MemberReport(
        String type,
        Map<String, Object> totals,
        Map<String, Integer> rows
    ) {
        MemberReport(String type, Map<String, Integer> rows) {
            this(type, Map.of(), rows);
        }

        static MemberReport overall(MemberStatisticsService.OverallStatistics stats) {
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("totalMembers", stats.totalMembers());
            totals.put("approvedMembers", stats.approvedMembers());
            totals.put("pendingMembers", stats.pendingMembers());
            totals.put("rejectedMembers", stats.rejectedMembers());
            totals.put("totalGroups", stats.totalGroups());
            totals.put("approvalRate", stats.approvalRate());
            totals.put("mostPopularGroup", stats.mostPopularGroup());
            totals.put("mostPopularSchool", stats.mostPopularSchool());
            return new MemberReport("overall", totals, Map.of());
        }
    }

    private String renderReport(MemberReport report, Map<String, Integer> trend, String format) {
        if ("json".equalsIgnoreCase(format)) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", report.type());
                payload.put("totals", report.totals());
                payload.put("rows", report.rows());
                if (!trend.isEmpty()) {
                    payload.put("trend", trend);
                }
                return EXPORT_MAPPER.writeValueAsString(payload);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw invalidArgument("Failed to render JSON report: " + e.getMessage());
            }
        }
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder("dimension,count\n");
            report.rows().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> csv.append(entry.getKey() == null ? "" : entry.getKey())
                    .append(',').append(entry.getValue()).append('\n'));
            if (!trend.isEmpty()) {
                csv.append("date,applications\n");
                trend.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> csv.append(entry.getKey())
                        .append(',').append(entry.getValue()).append('\n'));
            }
            return csv.toString();
        }
        StringBuilder summary = new StringBuilder();
        summary.append("# 成员").append(report.type()).append("报告\n\n");
        report.totals().forEach((key, value) -> summary.append("- ")
            .append(key).append(": ").append(value).append('\n'));
        if (!report.rows().isEmpty()) {
            summary.append('\n').append("## 分布\n\n");
            report.rows().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> summary.append("- ")
                    .append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        }
        if (!trend.isEmpty()) {
            summary.append('\n').append("## 每日申请\n\n");
            trend.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> summary.append("- ")
                    .append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        }
        return summary.toString();
    }

    private McpToolDefinition memberDuplicateCheckTool() {
        return McpToolDefinition.builder()
            .name("member_duplicate_check")
            .title("Member duplicate check")
            .description("Scan all members and report clusters that share the same email, QQ "
                + "or school. Useful before approvals or merges.")
            .displayTitle("成员重复检查")
            .displayDescription("扫描全部成员，按邮箱、QQ 或学校列出重复成员簇。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "field", enumProperty(List.of("email", "qq", "school"), "Field to compare. "
                    + "Default 'email'."),
                "minCount", integerProperty("Minimum members per cluster. Default 2.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Member duplicate check"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var fieldArg = stringArg(args, "field");
                final String field = fieldArg != null ? fieldArg : "email";
                final int minCount = Math.max(2, intArg(args, "minCount", 2));

                var query = new MemberQueryService.MemberQuery(
                    1, MemberQueryService.MAX_SIZE, null, null, null, null);
                return memberQueryService.listMembers(query)
                    .map(result -> {
                        Map<String, List<MemberVo>> byValue = result.getItems().stream()
                            .filter(member -> {
                                String value = duplicateValue(member, field);
                                return value != null && !value.isBlank();
                            })
                            .collect(Collectors.groupingBy(
                                member -> duplicateValue(member, field).toLowerCase(),
                                LinkedHashMap::new,
                                Collectors.toList()));

                        var clusters = byValue.entrySet().stream()
                            .filter(entry -> entry.getValue().size() >= minCount)
                            .map(entry -> {
                                Map<String, Object> cluster = new LinkedHashMap<>();
                                cluster.put("value", entry.getKey());
                                cluster.put("count", entry.getValue().size());
                                cluster.put("members", entry.getValue().stream()
                                    .map(this::memberSummary)
                                    .toList());
                                return cluster;
                            })
                            .toList();

                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("field", field);
                        content.put("minCount", minCount);
                        content.put("scannedMembers", result.getTotal());
                        content.put("clusterCount", clusters.size());
                        content.put("clusters", clusters);
                        return McpToolResult.success(content,
                            "Found %d duplicate clusters by %s".formatted(clusters.size(), field));
                    });
            }))
            .build();
    }

    private static String duplicateValue(MemberVo member, String field) {
        var spec = member.getSpec();
        return switch (field) {
            case "qq" -> spec.getQq();
            case "school" -> spec.getSchool();
            default -> spec.getEmail();
        };
    }

    private Mono<Boolean> canView() {
        return hasRole(VIEW_ROLE);
    }

    private Mono<Boolean> canManage() {
        return hasRole(MANAGE_ROLE);
    }

    /**
     * Halo 内置超级管理员会被 {@link RoleService#contains} 直接放行；其余账号按角色模板及其
     * 依赖关系判断，因此自定义角色只要声明依赖也能获得授权。
     */
    private Mono<Boolean> hasRole(String role) {
        return currentUsername()
            .flatMap(username -> roleService.getRolesByUsername(username)
                .collect(Collectors.toSet()))
            .flatMap(roleNames -> roleService.contains(roleNames, Set.of(role)))
            .defaultIfEmpty(false);
    }

    private Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getName)
            .filter(name -> !name.isBlank());
    }

    private Mono<McpToolResult> execute(McpToolInvocation invocation, Mono<Boolean> permitted,
        Function<Map<String, Object>, Mono<McpToolResult>> action) {
        var arguments = invocation.arguments() == null
            ? Map.<String, Object>of() : invocation.arguments();
        return permitted.flatMap(granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                return Mono.just(McpToolResult.error("FORBIDDEN",
                    "The access key owner needs the member view or manage permission."));
            }
            return action.apply(arguments);
        });
    }

    private Map<String, Object> pageContent(ListResult<MemberVo> result) {
        var items = result.getItems().stream().map(this::memberSummary).toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("page", result.getPage());
        content.put("size", result.getSize());
        content.put("total", result.getTotal());
        content.put("items", items);
        return content;
    }

    private String summary(ListResult<MemberVo> result) {
        return "%d of %d members on page %d".formatted(
            result.getItems().size(), result.getTotal(), result.getPage());
    }

    private Map<String, Object> memberSummary(MemberVo member) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", member.getMetadata().getName());
        content.put("displayName", member.getSpec().getDisplayName());
        content.put("school", member.getSpec().getSchool());
        content.put("groupName", member.getSpec().getGroupName());
        content.put("status", member.getSpec().getStatus());
        return content;
    }

    private Map<String, Object> memberDetail(Member member) {
        var spec = member.getSpec();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", member.getMetadata().getName());
        content.put("displayName", spec.getDisplayName());
        content.put("email", spec.getEmail());
        content.put("school", spec.getSchool());
        content.put("qq", spec.getQq());
        content.put("groupName", spec.getGroupName());
        content.put("status", spec.getStatus());
        content.put("origin", spec.getOrigin());
        content.put("createdAt", member.getMetadata().getCreationTimestamp());
        var annotations = member.getMetadata().getAnnotations();
        if (annotations != null) {
            content.put("reviewDescription", annotations.get(MemberConstant.REVIEW_DESCRIPTION));
            content.put("withdrawReason", annotations.get(MemberConstant.WITHDRAW_REASON));
        }
        return content;
    }

    private Map<String, Object> groupContent(MemberGroupVo group) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", group.getMetadata().getName());
        content.put("displayName", group.getSpec().getDisplayName());
        content.put("description", group.getSpec().getDescription());
        return content;
    }

    private Map<String, Object> batchContent(MemberBatchService.BatchOperationResult result) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("total", result.total());
        content.put("success", result.success());
        content.put("failed", result.failed());
        content.put("failedMembers", result.failedMembers());
        content.put("message", result.message());
        return content;
    }

    private static List<String> requireNames(Map<String, Object> args) {
        var names = optionalNames(args, "names");
        if (names == null || names.isEmpty()) {
            throw invalidArgument("Argument 'names' must be a non-empty array of strings.");
        }
        return names;
    }

    private static List<String> optionalNames(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof List<?> list)) {
            return null;
        }
        var result = list.stream()
            .map(name -> name == null ? null : String.valueOf(name).trim())
            .filter(name -> name != null && !name.isEmpty())
            .distinct()
            .toList();
        return result.isEmpty() ? null : result;
    }

    private static boolean booleanArg(Map<String, Object> args, String key,
        boolean defaultValue) {
        return switch (args.get(key)) {
            case Boolean value -> value;
            case String value -> "true".equalsIgnoreCase(value.trim());
            case null, default -> defaultValue;
        };
    }

    /**
     * 接受 ISO 8601 时间点（2026-01-01T00:00:00Z）或纯日期（2026-01-01，按 UTC 起始计）。
     */
    private static Instant instantArg(Map<String, Object> args, String key) {
        var value = stringArg(args, key);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ex) {
                throw invalidArgument("Argument '" + key + "' must be an ISO 8601 instant "
                    + "or date.");
            }
        }
    }

    private static String requireString(Map<String, Object> args, String key) {
        var value = stringArg(args, key);
        if (value == null) {
            throw invalidArgument("Argument '" + key + "' is required.");
        }
        return value;
    }

    private static String stringArg(Map<String, Object> args, String key) {
        return switch (args.get(key)) {
            case null -> null;
            case String value -> value.isBlank() ? null : value.trim();
            case Number value -> value.toString();
            case Boolean value -> value.toString();
            default -> null;
        };
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        return switch (args.get(key)) {
            case null -> defaultValue;
            case Number value -> value.intValue();
            case String value -> {
                try {
                    yield Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    yield defaultValue;
                }
            }
            default -> defaultValue;
        };
    }

    private static boolean requireBoolean(Map<String, Object> args, String key) {
        return switch (args.get(key)) {
            case Boolean value -> value;
            case String value -> switch (value.trim().toLowerCase()) {
                case "true" -> true;
                case "false" -> false;
                default -> throw invalidArgument(
                    "Argument '" + key + "' must be a boolean.");
            };
            default -> throw invalidArgument("Argument '" + key + "' is required.");
        };
    }

    private static McpToolException invalidArgument(String message) {
        return new McpToolException("INVALID_ARGUMENT", message);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties,
        List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        return property("string", description);
    }

    private static Map<String, Object> integerProperty(String description) {
        return property("integer", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return property("boolean", description);
    }

    private static Map<String, Object> stringArrayProperty(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", property("string", null));
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> enumProperty(List<String> values, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (description != null) {
            schema.put("description", description);
        }
        return schema;
    }
}
