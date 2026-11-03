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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * 成员管理 MCP 工具：成员查询、分组管理、统计、导入导出、申请与撤回审核、资料修改、
 * 学校关联分析与认领令牌治理。
 *
 * <p>工具只做参数解析与服务编排，业务规则仍由插件既有服务承担。破坏性操作保持克制：
 * 成员本身不提供删除/合并，分组删除只允许删空分组，避免成员失去归属。
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
    private static final String CATEGORY_AFFINITY = "成员关系";

    private static final int MAX_SCHOOLS = 40;

    private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper();

    private static final Set<String> MEMBER_STATUSES =
        Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAW_REQUESTED");

    /** 数据巡检项：重复、联系方式、学校、分组、头像、状态。 */
    private static final List<String> AUDIT_CHECKS = List.of(
        "duplicate", "contact", "school", "group", "avatar", "status");

    /**
     * 概览快照的分区。mcp-server 1.3.x 的 API 只有 tools，没有 resources，
     * 因此用一次性快照工具替代 {@code members://overview} 这类资源读取。
     */
    private static final List<String> OVERVIEW_SECTIONS = List.of(
        "core", "groups", "schools", "queues", "health", "trend");

    /** 快照资源的稳定标识，供客户端在对话里引用同一份数据。 */
    private static final String OVERVIEW_URI = "members://overview";

    /** 成员摘要可投影的字段，用于按需裁剪返回体积。 */
    private static final List<String> SUMMARY_FIELDS = List.of(
        "name", "displayName", "school", "groupName", "status");

    /** 未请求 core 分区时占位，避免为拿一个空壳去调用统计服务。 */
    private static final MemberStatisticsService.OverallStatistics EMPTY_OVERALL_STATISTICS =
        new MemberStatisticsService.OverallStatistics(0, 0, 0, 0, 0, 0.0, null, null);

    private final RoleService roleService;
    private final ReactiveExtensionClient client;
    private final MemberFinder memberFinder;
    private final MemberQueryService memberQueryService;
    private final MemberBatchService memberBatchService;
    private final MemberStatisticsService statisticsService;
    private final MemberWithdrawReviewService withdrawReviewService;
    private final ClaimTokenService claimTokenService;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final SettingConfigMember settingConfigMember;
    private final ObjectProvider<SchoolAffinityService> affinityServiceProvider;

    public MemberMcpToolProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService,
        MemberStatisticsService statisticsService,
        MemberWithdrawReviewService withdrawReviewService,
        ClaimTokenService claimTokenService,
        ExternalLinkProcessor externalLinkProcessor,
        SettingConfigMember settingConfigMember,
        ObjectProvider<SchoolAffinityService> affinityServiceProvider) {
        this.roleService = roleService;
        this.client = client;
        this.memberFinder = memberFinder;
        this.memberQueryService = memberQueryService;
        this.memberBatchService = memberBatchService;
        this.statisticsService = statisticsService;
        this.withdrawReviewService = withdrawReviewService;
        this.claimTokenService = claimTokenService;
        this.externalLinkProcessor = externalLinkProcessor;
        this.settingConfigMember = settingConfigMember;
        this.affinityServiceProvider = affinityServiceProvider;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            listMembersTool(),
            getMemberTool(),
            listMemberGroupsTool(),
            createMemberGroupTool(),
            updateMemberGroupTool(),
            deleteMemberGroupTool(),
            getMemberStatisticsTool(),
            getMemberOverviewTool(),
            reviewMemberSubmissionsTool(),
            reviewMemberWithdrawalsTool(),
            listMemberWithdrawalsTool(),
            assignMemberGroupTool(),
            updateMemberTool(),
            exportMemberDataTool(),
            batchImportMembersTool(),
            batchUpdateMembersTool(),
            searchMembersAdvancedTool(),
            generateMemberReportTool(),
            memberDuplicateCheckTool(),
            auditMemberDataTool(),
            analyzeSchoolAffinityTool(),
            autoApprovePendingTool(),
            autoAssignGroupTool(),
            generateClaimTokensTool(),
            listClaimTokensTool(),
            revokeClaimTokenTool(),
            validateQqLinkTool());
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
                    "name-asc", "name-desc"), "Sort order."),
                "fields", enumArrayProperty(List.copyOf(SUMMARY_FIELDS),
                    "Fields to return per member. Defaults to all of them; request fewer "
                        + "fields to keep large pages small.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("List members"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var page = intArg(args, "page", 1);
                var size = Math.clamp(intArg(args, "size", 20), 1, 100);
                var requestedFields = optionalNames(args, "fields");
                final var fields = requestedFields != null ? requestedFields : SUMMARY_FIELDS;
                if (!SUMMARY_FIELDS.containsAll(fields)) {
                    var unknown = fields.stream()
                        .filter(field -> !SUMMARY_FIELDS.contains(field))
                        .toList();
                    throw invalidArgument("Unknown fields " + unknown + ", expected "
                        + SUMMARY_FIELDS + ".");
                }
                var query = new MemberQueryService.MemberQuery(
                    page,
                    size,
                    stringArg(args, "keyword"),
                    stringArg(args, "status"),
                    stringArg(args, "groupName"),
                    stringArg(args, "sort"));
                return memberQueryService.listMembers(query)
                    .map(result -> McpToolResult.success(pageContent(result, fields),
                        summary(result)));
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

    private McpToolDefinition createMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("create_member_group")
            .title("Create member group")
            .description("Create a member group. The display name must be unique; move members "
                + "into it afterwards with assign_member_group.")
            .displayTitle("新建成员分组")
            .displayDescription("新建成员分组，名称不可重复；建好后可用调整分组工具移入成员。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "displayName", stringProperty("Group name shown on the site."),
                "description", stringProperty("Optional group description."),
                "name", stringProperty("Optional metadata.name; generated from the display "
                    + "name when omitted.")),
                List.of("displayName")))
            .annotations(McpToolAnnotations.defaults("Create member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var displayName = requireString(args, "displayName");
                final var description = stringArg(args, "description");
                final var explicitName = stringArg(args, "name");
                return memberFinder.listAllGroups()
                    .collectList()
                    .flatMap(groups -> {
                        var duplicated = groups.stream().anyMatch(group -> {
                            var spec = group.getSpec();
                            return spec != null && spec.getDisplayName() != null
                                && displayName.equalsIgnoreCase(spec.getDisplayName().trim());
                        });
                        if (duplicated) {
                            return Mono.just(McpToolResult.error("GROUP_EXISTS",
                                "Member group " + displayName + " already exists."));
                        }
                        var group = new MemberGroup();
                        var metadata = new Metadata();
                        metadata.setName(explicitName != null ? explicitName
                            : generateGroupName(displayName));
                        group.setMetadata(metadata);
                        var spec = new MemberGroup.MemberGroupSpec();
                        spec.setDisplayName(displayName);
                        spec.setDescription(description);
                        group.setSpec(spec);
                        return client.create(group)
                            .map(created -> McpToolResult.success(
                                groupContent(MemberGroupVo.from(created)),
                                "Member group " + displayName + " created"));
                    });
            }))
            .build();
    }

    private McpToolDefinition updateMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("update_member_group")
            .title("Update member group")
            .description("Rename a member group or change its description. Members keep their "
                + "group membership because the rename is cascaded to every member that "
                + "referenced the old name.")
            .displayTitle("修改成员分组")
            .displayDescription("重命名分组或修改说明；重命名会同步更新成员的所属分组。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Group metadata.name."),
                "displayName", stringProperty("New group name shown on the site."),
                "description", stringProperty("New group description; pass an empty string to "
                    + "clear it.")),
                List.of("name")))
            .annotations(McpToolAnnotations.defaults("Update member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var name = requireString(args, "name");
                final var newDisplayName = stringArg(args, "displayName");
                final var hasDescription = args.containsKey("description");
                final var newDescription = stringArg(args, "description");
                if (newDisplayName == null && !hasDescription) {
                    throw invalidArgument("Provide 'displayName' or 'description' to update.");
                }
                return client.fetch(MemberGroup.class, name)
                    .switchIfEmpty(Mono.error(new McpToolException("NOT_FOUND",
                        "Member group " + name + " was not found.")))
                    .flatMap(group -> {
                        var spec = group.getSpec() != null ? group.getSpec()
                            : new MemberGroup.MemberGroupSpec();
                        final String previousName = spec.getDisplayName();
                        if (newDisplayName != null) {
                            spec.setDisplayName(newDisplayName);
                        }
                        if (hasDescription) {
                            spec.setDescription(newDescription);
                        }
                        group.setSpec(spec);
                        return client.update(group).flatMap(updated -> {
                            var content = groupContent(MemberGroupVo.from(updated));
                            if (previousName == null || newDisplayName == null
                                || previousName.equals(newDisplayName)) {
                                content.put("membersMoved", 0L);
                                return Mono.just(McpToolResult.success(content,
                                    "Member group " + name + " updated"));
                            }
                            content.put("renamedFrom", previousName);
                            return renameGroupMembers(previousName, newDisplayName)
                                .map(moved -> {
                                    content.put("membersMoved", moved);
                                    return McpToolResult.success(content,
                                        "Member group renamed to " + newDisplayName
                                            + ", " + moved + " member(s) moved");
                                });
                        });
                    });
            }))
            .build();
    }

    private Mono<Long> renameGroupMembers(String previousName, String newName) {
        return client.list(Member.class, member -> member.getSpec() != null
                && previousName.equals(member.getSpec().getGroupName()), null)
            .concatMap(member -> {
                member.getSpec().setGroupName(newName);
                return client.update(member);
            })
            .count();
    }

    private McpToolDefinition deleteMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("delete_member_group")
            .title("Delete member group")
            .description("Delete an empty member group. Groups that still contain members are "
                + "rejected, so move or ungroup those members first.")
            .displayTitle("删除成员分组")
            .displayDescription("删除空分组；分组内仍有成员时拒绝删除，避免成员失去归属。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Group metadata.name.")),
                List.of("name")))
            .annotations(McpToolAnnotations.defaults("Delete member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var name = requireString(args, "name");
                return client.fetch(MemberGroup.class, name)
                    .switchIfEmpty(Mono.error(new McpToolException("NOT_FOUND",
                        "Member group " + name + " was not found.")))
                    .flatMap(group -> {
                        var spec = group.getSpec();
                        var displayName = spec == null ? null : spec.getDisplayName();
                        if (displayName == null || displayName.isBlank()) {
                            return client.delete(group).thenReturn(
                                McpToolResult.success(Map.of("name", name, "deleted", true),
                                    "Member group " + name + " deleted"));
                        }
                        return client.list(Member.class, member -> member.getSpec() != null
                                && displayName.equals(member.getSpec().getGroupName()), null)
                            .count()
                            .flatMap(count -> {
                                if (count > 0) {
                                    return Mono.just(McpToolResult.error("GROUP_NOT_EMPTY",
                                        "Member group " + displayName + " still has " + count
                                            + " member(s). Reassign them first."));
                                }
                                return client.delete(group).thenReturn(McpToolResult.success(
                                    Map.of("name", name, "displayName", displayName,
                                        "deleted", true),
                                    "Member group " + displayName + " deleted"));
                            });
                    });
            }))
            .build();
    }

    private static String generateGroupName(String displayName) {
        var slug = displayName.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) {
            slug = "group";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug + "-" + UUID.randomUUID().toString().substring(0, 6);
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

    private McpToolDefinition getMemberOverviewTool() {
        return McpToolDefinition.builder()
            .name("get_member_overview")
            .title("Get member overview")
            .description("Read a one-shot snapshot of the member directory: totals, status and "
                + "group/school distribution, pending work queues, data quality headline and "
                + "recent application trend. Prefer this over chaining get_member_statistics, "
                + "list_member_groups, list_members and audit_member_data; narrow the payload "
                + "with sections instead.")
            .displayTitle("成员概览快照")
            .displayDescription("一次读取成员花名册快照：总量与通过率、分组与学校分布、待办队列、"
                + "数据质量摘要、申请趋势。可用 sections 只取需要的部分，避免多次调用工具。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "sections", enumArrayProperty(List.copyOf(OVERVIEW_SECTIONS),
                    "Sections to include. Defaults to every section."),
                "schoolLimit", integerProperty("Maximum schools listed in the distribution. "
                    + "1-40, default 10."),
                "trendDays", integerProperty("Days covered by the application trend. "
                    + "1-90, default 14.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Get member overview"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var requested = optionalNames(args, "sections");
                final var sections = requested != null ? requested : OVERVIEW_SECTIONS;
                var unknown = sections.stream()
                    .filter(section -> !OVERVIEW_SECTIONS.contains(section))
                    .toList();
                if (!unknown.isEmpty()) {
                    throw invalidArgument("Unknown sections " + unknown + ", expected "
                        + OVERVIEW_SECTIONS + ".");
                }
                final int schoolLimit =
                    Math.clamp(intArg(args, "schoolLimit", 10), 1, MAX_SCHOOLS);
                final int trendDays = Math.clamp(intArg(args, "trendDays", 14), 1, 90);
                // 只取被请求的分区所依赖的数据：裁剪 sections 时不再白跑统计与全表扫描。
                final boolean needsMembers = sections.contains("groups")
                    || sections.contains("queues") || sections.contains("health");
                final boolean needsGroups = sections.contains("groups")
                    || sections.contains("health");
                // 成员与分组只读一次：待办队列和数据质量基于同一份快照，
                // 避免两次读取之间的漂移，也让全表扫描只发生一次。
                var snapshotMono = Mono.zip(
                    needsMembers
                        ? client.list(Member.class, member -> true, null).collectList()
                        : Mono.just(List.<Member>of()),
                    needsGroups
                        ? memberFinder.listAllGroups().collectList()
                        : Mono.just(List.<MemberGroupVo>of()));
                return snapshotMono.flatMap(snapshot -> {
                    var members = snapshot.getT1();
                    var groups = snapshot.getT2();
                    return Mono.zip(
                            sections.contains("core")
                                ? statisticsService.getOverallStatistics()
                                : Mono.just(EMPTY_OVERALL_STATISTICS),
                            sections.contains("groups")
                                ? statisticsService.getGroupStatistics()
                                : Mono.just(Map.<String, Integer>of()),
                            sections.contains("schools")
                                ? statisticsService.getSchoolStatistics()
                                : Mono.just(Map.<String, Integer>of()),
                            sections.contains("trend")
                                ? statisticsService.getApplicationTrend(trendDays)
                                : Mono.just(Map.<String, Integer>of()))
                        .map(stats -> overviewResult(stats.getT1(), stats.getT2(),
                            stats.getT3(), stats.getT4(), members, groups, sections,
                            schoolLimit, trendDays));
                });
            }))
            .build();
    }

    private McpToolResult overviewResult(
        MemberStatisticsService.OverallStatistics overall,
        Map<String, Integer> byGroup,
        Map<String, Integer> bySchool,
        Map<String, Integer> trend,
        List<Member> members,
        List<MemberGroupVo> groups,
        List<String> sections,
        int schoolLimit,
        int trendDays) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("resource", OVERVIEW_URI);
        content.put("generatedAt", Instant.now().toString());
        content.put("sections", sections);

        if (sections.contains("core")) {
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("totalMembers", overall.totalMembers());
            core.put("approvedMembers", overall.approvedMembers());
            core.put("pendingMembers", overall.pendingMembers());
            core.put("rejectedMembers", overall.rejectedMembers());
            core.put("totalGroups", overall.totalGroups());
            core.put("approvalRate", overall.approvalRate());
            core.put("mostPopularGroup", overall.mostPopularGroup());
            core.put("mostPopularSchool", overall.mostPopularSchool());
            content.put("core", core);
        }

        if (sections.contains("groups")) {
            content.put("groups", groupDistribution(byGroup, groups, members));
        }

        if (sections.contains("schools")) {
            var schools = bySchool == null ? Map.<String, Integer>of() : bySchool;
            var sorted = schools.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .toList();
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("distinctSchools", sorted.size());
            section.put("items", sorted.stream()
                .limit(schoolLimit)
                .map(entry -> distributionItem(entry.getKey(), entry.getValue()))
                .toList());
            section.put("truncated", sorted.size() > schoolLimit);
            content.put("schools", section);
        }

        if (sections.contains("queues")) {
            content.put("queues", workQueues(members));
        }

        if (sections.contains("health")) {
            content.put("health", healthSummary(members, groups));
        }

        if (sections.contains("trend")) {
            var applications = trend == null ? Map.<String, Integer>of() : trend;
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("days", trendDays);
            section.put("items", applications.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> distributionItem(entry.getKey(), entry.getValue()))
                .toList());
            content.put("trend", section);
        }

        return McpToolResult.success(content, overviewSummary(content, members.size()));
    }

    private List<Map<String, Object>> groupDistribution(Map<String, Integer> byGroup,
        List<MemberGroupVo> groups, List<Member> members) {
        var counts = new LinkedHashMap<String, Integer>();
        // 先按分组定义排好顺序，未使用的分组也要出现，方便发现空分组。
        for (var group : groups) {
            var spec = group.getSpec();
            if (spec != null && spec.getDisplayName() != null) {
                counts.putIfAbsent(spec.getDisplayName().trim(), 0);
            }
        }
        if (byGroup != null) {
            byGroup.forEach((name, count) -> counts.put(name, count));
        }
        long ungrouped = members.stream()
            .filter(member -> {
                var spec = member.getSpec();
                return spec == null || spec.getGroupName() == null
                    || spec.getGroupName().isBlank();
            })
            .count();
        var items = counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> distributionItem(entry.getKey(), entry.getValue()))
            .collect(Collectors.toCollection(ArrayList::new));
        items.add(0, distributionItem("（未分组）", (int) ungrouped));
        return items;
    }

    private Map<String, Object> workQueues(List<Member> members) {
        long pending = countByStatus(members, "PENDING");
        long withdrawals = countByStatus(members, "WITHDRAW_REQUESTED");
        long ungroupedApproved = members.stream()
            .filter(member -> {
                var spec = member.getSpec();
                return spec != null && "APPROVED".equals(spec.getStatus())
                    && (spec.getGroupName() == null || spec.getGroupName().isBlank());
            })
            .count();
        Map<String, Object> queues = new LinkedHashMap<>();
        queues.put("pendingApplications", pending);
        queues.put("withdrawRequests", withdrawals);
        queues.put("approvedWithoutGroup", ungroupedApproved);
        return queues;
    }

    private Map<String, Object> healthSummary(List<Member> members, List<MemberGroupVo> groups) {
        var reports = auditCounts(members, groups);
        long issueTotal = reports.stream()
            .mapToLong(report -> ((Long) report.get("count")))
            .sum();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("scanned", members.size());
        health.put("issueTotal", issueTotal);
        health.put("healthy", issueTotal == 0);
        health.put("checks", reports);
        return health;
    }

    private static long countByStatus(List<Member> members, String status) {
        return members.stream()
            .filter(member -> {
                var spec = member.getSpec();
                return spec != null && status.equals(spec.getStatus());
            })
            .count();
    }

    private static Map<String, Object> distributionItem(String key, Integer count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", key);
        item.put("count", count == null ? 0 : count);
        return item;
    }

    private String overviewSummary(Map<String, Object> content, int scanned) {
        var parts = new ArrayList<String>();
        parts.add(scanned + " members");
        if (content.get("queues") instanceof Map<?, ?> queues) {
            parts.add(queues.get("pendingApplications") + " pending");
            parts.add(queues.get("withdrawRequests") + " withdrawals");
        }
        if (content.get("health") instanceof Map<?, ?> health) {
            parts.add(health.get("issueTotal") + " data issues");
        }
        return String.join(" · ", parts);
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

    private McpToolDefinition listClaimTokensTool() {
        return McpToolDefinition.builder()
            .name("list_claim_tokens")
            .title("List claim tokens")
            .description("List outstanding claim tokens with their expiry, so the admin can "
                + "reuse a link that is still valid instead of generating another one. "
                + "Tokens are credentials: return them only to the admin who asked.")
            .displayTitle("认领链接清单")
            .displayDescription("查看仍有效的认领令牌与过期时间，避免重复生成。令牌属于凭据，仅向管理员展示。")
            .category(CATEGORY_CLAIM)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Optional member names to inspect."),
                "includeExpired", booleanProperty("Include expired tokens. Default false."),
                "limit", integerProperty("Maximum tokens to return. 1-500, default 100.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("List claim tokens"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var names = optionalNames(args, "names");
                final boolean includeExpired = booleanArg(args, "includeExpired", false);
                final int limit = Math.clamp(intArg(args, "limit", 100), 1, 500);
                return client.list(Member.class, member -> annotation(member,
                        ClaimTokenService.CLAIM_TOKEN_ANNOTATION) != null, null)
                    .map(this::tokenItem)
                    .filter(item -> includeExpired || !Boolean.TRUE.equals(item.get("expired")))
                    .filter(item -> names == null || names.contains(item.get("name")))
                    .take(limit)
                    .collectList()
                    .map(items -> {
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("total", items.size());
                        content.put("items", items);
                        return McpToolResult.success(content,
                            items.size() + " claim token(s)");
                    });
            }))
            .build();
    }

    private Map<String, Object> tokenItem(Member member) {
        var token = annotation(member, ClaimTokenService.CLAIM_TOKEN_ANNOTATION);
        var expiresAt = annotation(member, ClaimTokenService.CLAIM_TOKEN_EXPIRES_ANNOTATION);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", member.getMetadata().getName());
        item.put("displayName", member.getSpec() == null ? null
            : member.getSpec().getDisplayName());
        item.put("token", token);
        item.put("claimUrl", token == null ? null : buildClaimUrl(token));
        item.put("expiresAt", expiresAt);
        item.put("expired", expiresAt != null && isExpired(expiresAt));
        return item;
    }

    private static boolean isExpired(String expiresAt) {
        try {
            return Instant.now().isAfter(Instant.parse(expiresAt));
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private McpToolDefinition revokeClaimTokenTool() {
        return McpToolDefinition.builder()
            .name("revoke_claim_token")
            .title("Revoke claim tokens")
            .description("Revoke claim tokens so the existing links stop working, for example "
                + "after the link was sent to the wrong person. Members without a token are "
                + "reported as NONE.")
            .displayTitle("作废认领链接")
            .displayDescription("作废指定成员的认领令牌，旧链接立即失效；无令牌的成员返回 NONE。")
            .category(CATEGORY_CLAIM)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names whose tokens should be "
                    + "revoked.")),
                List.of("names")))
            .annotations(McpToolAnnotations.defaults("Revoke claim tokens"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var names = requireNames(args);
                return Flux.fromIterable(names)
                    .concatMap(name -> client.fetch(Member.class, name)
                        .flatMap(member -> {
                            if (annotation(member,
                                ClaimTokenService.CLAIM_TOKEN_ANNOTATION) == null) {
                                return Mono.just(reviewItem(name, "NONE",
                                    "No claim token to revoke."));
                            }
                            return claimTokenService.invalidateToken(name)
                                .map(saved -> reviewItem(name, "REVOKED",
                                    "Claim token revoked."));
                        })
                        .switchIfEmpty(Mono.fromSupplier(() -> reviewItem(name, "NOT_FOUND",
                            "Member " + name + " was not found.")))
                        .onErrorResume(error -> Mono.just(reviewItem(name, "FAILED",
                            error.getMessage() != null ? error.getMessage() : "unknown"))))
                    .collectList()
                    .map(items -> {
                        long revoked = items.stream()
                            .filter(item -> "REVOKED".equals(item.get("status")))
                            .count();
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("total", items.size());
                        content.put("revoked", revoked);
                        content.put("items", items);
                        return McpToolResult.success(content,
                            revoked + " of " + items.size() + " claim token(s) revoked");
                    });
            }))
            .build();
    }

    private McpToolDefinition validateQqLinkTool() {
        return McpToolDefinition.builder()
            .name("validate_qq_link")
            .title("Validate QQ friend link")
            .description("Validate and normalize a QQ friend link before storing it. Accepts "
                + "https://qm.qq.com/q/<token> and https://qm.qq.com/cgi-bin/qm/qr?k=<token>, "
                + "and adds the https scheme when it is missing.")
            .displayTitle("校验 QQ 加好友链接")
            .displayDescription("校验并归一化 QQ 加好友链接，支持 /q/ 与 /cgi-bin/qm/qr 两种官方形态。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "link", stringProperty("Candidate QQ friend link.")),
                List.of("link")))
            .annotations(McpToolAnnotations.readOnly("Validate QQ friend link"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var raw = requireString(args, "link");
                final var normalized = normalizeQqLinkArg(raw);
                var message = normalized.isEmpty() ? QqLinkUtils.RULE_MESSAGE : null;
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("valid", message == null);
                content.put("link", normalized.isEmpty() ? raw.trim() : normalized);
                content.put("message", message);
                return Mono.just(McpToolResult.success(content, message == null
                    ? "Link accepted" : message));
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
            .description("Update a member's display name, email, school, QQ, group, QQ friend "
                + "link, join date or background image. Omitted fields stay unchanged; "
                + "approved members are reflected on the public members page immediately. "
                + "Empty strings clear the friend link, join date or background.")
            .displayTitle("修改成员资料")
            .displayDescription("修改成员昵称、邮箱、学校、QQ、分组、加好友链接、加入时间或背景图，"
                + "未提供的字段保持不变，空字符串表示清空。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Member account name (metadata.name)."),
                "displayName", stringProperty("New display name."),
                "email", stringProperty("New email address."),
                "school", stringProperty("New school name."),
                "qq", stringProperty("New QQ number."),
                "groupName", stringProperty("Move the member to this group."),
                "qqFriendLink", stringProperty("New QQ friend link; validated against "
                    + "qm.qq.com rules."),
                "joinedAt", stringProperty("Join date, ISO 8601 date or instant, e.g. "
                    + "2026-03-01."),
                "background", stringProperty("Background image URL.")),
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
                final boolean hasFriendLink = args.containsKey("qqFriendLink");
                final boolean hasJoinedAt = args.containsKey("joinedAt");
                final boolean hasBackground = args.containsKey("background");
                final var friendLinkInput = stringArg(args, "qqFriendLink");
                final var joinedAtInput = stringArg(args, "joinedAt");
                final var backgroundInput = stringArg(args, "background");
                if (displayName == null && email == null && school == null && qq == null
                    && groupName == null && !hasFriendLink && !hasJoinedAt && !hasBackground) {
                    throw invalidArgument("Provide at least one field to update.");
                }
                final String friendLink;
                if (!hasFriendLink) {
                    friendLink = null;
                } else if (friendLinkInput == null) {
                    friendLink = "";
                } else {
                    friendLink = normalizeQqLinkArg(friendLinkInput);
                    if (friendLink.isEmpty()) {
                        throw invalidArgument("Argument 'qqFriendLink' is not an official "
                            + "qm.qq.com link. " + QqLinkUtils.RULE_MESSAGE);
                    }
                }
                final String joinedAt;
                if (!hasJoinedAt) {
                    joinedAt = null;
                } else if (joinedAtInput == null) {
                    joinedAt = "";
                } else {
                    var instant = instantArg(args, "joinedAt");
                    joinedAt = instant == null ? "" : instant.toString();
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
                        if (friendLink != null) {
                            spec.setQqFriendLink(friendLink.isEmpty() ? null : friendLink);
                        }
                        if (joinedAt != null) {
                            spec.setJoinedAt(joinedAt.isEmpty() ? null : joinedAt);
                        }
                        if (hasBackground) {
                            spec.setBackground(backgroundInput);
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
    
    private McpToolDefinition batchUpdateMembersTool() {
        return McpToolDefinition.builder()
            .name("batch_update_members")
            .title("Batch update members")
            .description("Apply the same field patch to many members at once: school, group, "
                + "QQ friend link, or clear the group. Defaults to a dry run that only reports "
                + "what would change; set dryRun=false to persist. Only fields listed here can "
                + "be changed — status changes go through the review tools.")
            .displayTitle("批量修改成员")
            .displayDescription("按同一份字段补丁批量修改成员（学校、分组、QQ 链接、清空分组）；"
                + "默认只预览，dryRun=false 才落库。状态变更请走审核工具。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names to update. Max 200."),
                "school", stringProperty("New school name for every listed member."),
                "groupName", stringProperty("Move every listed member into this group."),
                "qqFriendLink", stringProperty("New QQ friend link; validated against "
                    + "qm.qq.com rules. Pass an empty string to clear it."),
                "clearGroup", booleanProperty("Remove the group assignment instead of setting "
                    + "one."),
                "dryRun", booleanProperty("Preview only. Default true.")),
                List.of("names")))
            .annotations(McpToolAnnotations.defaults("Batch update members"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = requireNames(args);
                if (names.size() > 200) {
                    throw invalidArgument("At most 200 members can be updated in one call.");
                }
                final boolean dryRun = booleanArg(args, "dryRun", true);
                final boolean clearGroup = booleanArg(args, "clearGroup", false);
                final var school = stringArg(args, "school");
                final var groupName = stringArg(args, "groupName");
                final boolean hasFriendLink = args.containsKey("qqFriendLink");
                final var friendLinkInput = stringArg(args, "qqFriendLink");
                if (clearGroup && groupName != null) {
                    throw invalidArgument("Provide either 'groupName' or clearGroup=true.");
                }
                if (school == null && groupName == null && !clearGroup && !hasFriendLink) {
                    throw invalidArgument("Provide at least one field to update.");
                }
                final String friendLink;
                if (!hasFriendLink) {
                    friendLink = null;
                } else if (friendLinkInput == null) {
                    friendLink = "";
                } else {
                    var normalized = normalizeQqLinkArg(friendLinkInput);
                    if (normalized.isEmpty()) {
                        throw invalidArgument("Argument 'qqFriendLink' is not an official "
                            + "qm.qq.com link. " + QqLinkUtils.RULE_MESSAGE);
                    }
                    friendLink = normalized;
                }
                return Flux.fromIterable(names)
                    .concatMap(name -> client.fetch(Member.class, name)
                        .flatMap(member -> {
                            var spec = member.getSpec();
                            // 先判定差异、再决定是否落笔：预览模式不能就地改实体，
                            // 否则调用方拿到的对象已经被"预演"污染。
                            boolean changed = false;
                            if (school != null && !school.equals(spec.getSchool())) {
                                changed = true;
                            }
                            if (groupName != null && !groupName.equals(spec.getGroupName())) {
                                changed = true;
                            }
                            if (clearGroup && !isBlank(spec.getGroupName())) {
                                changed = true;
                            }
                            if (friendLink != null && !friendLink.equals(
                                spec.getQqFriendLink() == null ? "" : spec.getQqFriendLink())) {
                                changed = true;
                            }
                            if (!changed) {
                                return Mono.just(reviewItem(name, "UNCHANGED",
                                    "Fields already match."));
                            }
                            if (dryRun) {
                                return Mono.just(reviewItem(name, "PLANNED",
                                    "Would be updated."));
                            }
                            if (school != null) {
                                spec.setSchool(school);
                            }
                            if (groupName != null) {
                                spec.setGroupName(groupName);
                            }
                            if (clearGroup) {
                                spec.setGroupName(null);
                            }
                            if (friendLink != null) {
                                spec.setQqFriendLink(friendLink.isEmpty() ? null : friendLink);
                            }
                            return client.update(member)
                                .map(saved -> reviewItem(name, "UPDATED", "Updated."));
                        })
                        .switchIfEmpty(Mono.fromSupplier(
                            () -> reviewItem(name, "NOT_FOUND", "Member was not found.")))
                        .onErrorResume(error -> Mono.just(reviewItem(name, "FAILED",
                            error.getMessage() != null ? error.getMessage() : "unknown"))))
                    .collectList()
                    .map(items -> {
                        var content = new LinkedHashMap<String, Object>();
                        content.put("dryRun", dryRun);
                        content.put("total", items.size());
                        content.put("updated", countStatus(items, dryRun ? "PLANNED" : "UPDATED"));
                        content.put("unchanged", countStatus(items, "UNCHANGED"));
                        content.put("failed", countStatus(items, "FAILED")
                            + countStatus(items, "NOT_FOUND"));
                        content.put("items", items);
                        var applied = (Long) content.get("updated");
                        return McpToolResult.success(content, dryRun
                            ? applied + " of " + items.size() + " member(s) would be updated"
                            : applied + " of " + items.size() + " member(s) updated");
                    });
            }))
            .build();
    }

    private static long countStatus(List<Map<String, Object>> items, String status) {
        return items.stream().filter(item -> status.equals(item.get("status"))).count();
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
                            .map(member -> memberSummary(member, SUMMARY_FIELDS))
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
                                    .map(member -> memberSummary(member, SUMMARY_FIELDS))
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

    private McpToolDefinition auditMemberDataTool() {
        return McpToolDefinition.builder()
            .name("audit_member_data")
            .title("Audit member data")
            .description("Scan every member and report data quality problems: duplicate QQ or "
                + "email, missing QQ or friend link, missing school, group names that no "
                + "longer exist, avatars that are not derived from the QQ number, and invalid "
                + "status values. Read-only; run it before exports or bulk fixes.")
            .displayTitle("成员数据巡检")
            .displayDescription("扫描全部成员的数据质量问题：重复 QQ/邮箱、缺 QQ 或加好友链接、"
                + "缺学校、分组已不存在、头像与 QQ 不一致、状态非法。只读，不修改数据。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "checks", enumArrayProperty(List.copyOf(AUDIT_CHECKS),
                    "Checks to run. Defaults to all of them."),
                "limit", integerProperty("Maximum sample items per check. 1-100, default 20.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Audit member data"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var requested = optionalNames(args, "checks");
                final var checks = requested != null ? requested : AUDIT_CHECKS;
                var unknown = checks.stream()
                    .filter(check -> !AUDIT_CHECKS.contains(check))
                    .toList();
                if (!unknown.isEmpty()) {
                    throw invalidArgument("Unknown checks " + unknown + ", expected "
                        + AUDIT_CHECKS + ".");
                }
                final int limit = Math.clamp(intArg(args, "limit", 20), 1, 100);
                return client.list(Member.class, member -> true, null)
                    .collectList()
                    .flatMap(members -> memberFinder.listAllGroups()
                        .collectList()
                        .map(groups -> auditResult(members, groups, checks, limit)));
            }))
            .build();
    }

    private McpToolResult auditResult(List<Member> members, List<MemberGroupVo> groups,
        List<String> checks, int limit) {
        var groupNames = groupIdentifiers(groups);
        var reports = new ArrayList<Map<String, Object>>();
        long issueTotal = 0;
        for (var check : checks) {
            var report = runAudit(check, members, groupNames, limit);
            issueTotal += (Long) report.get("count");
            reports.add(report);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("scanned", members.size());
        content.put("issueTotal", issueTotal);
        content.put("healthy", issueTotal == 0);
        content.put("checks", reports);
        return McpToolResult.success(content,
            issueTotal == 0 ? "No data quality issues found"
                : issueTotal + " data quality issue(s) across "
                    + reports.stream().filter(r -> ((Long) r.get("count")) > 0).count()
                    + " check(s)");
    }

    /**
     * 分组的可识别标识集合。
     *
     * <p>成员的 {@code groupName} 存的是分组**资源名**（如 {@code member-group-xxxx}），
     * 但老数据里也可能残留显示名（如「高校联盟」），导入时两者都会做双向解析。
     * 因此这里同时收集资源名与显示名，避免把「分组存在但存法不同」误报成分组缺失。
     */
    private static Set<String> groupIdentifiers(List<MemberGroupVo> groups) {
        return groups.stream()
            .filter(group -> group != null && group.getMetadata() != null
                && group.getMetadata().getName() != null)
            .flatMap(group -> {
                var identifiers = new ArrayList<String>();
                identifiers.add(group.getMetadata().getName());
                if (group.getSpec() != null && group.getSpec().getDisplayName() != null) {
                    identifiers.add(group.getSpec().getDisplayName());
                }
                return identifiers.stream();
            })
            .map(String::trim)
            .filter(identifier -> !identifier.isEmpty())
            .collect(Collectors.toSet());
    }

    private Map<String, Object> runAudit(String check, List<Member> members,
        Set<String> groupNames, int limit) {
        return switch (check) {
            case "duplicate" -> duplicateAudit(members, limit);
            case "contact" -> contactAudit(members, limit);
            case "school" -> fieldAudit(members, limit, "school");
            case "group" -> danglingGroupAudit(members, groupNames, limit);
            case "avatar" -> avatarAudit(members, limit);
            default -> statusAudit(members, limit);
        };
    }

    /**
     * 只保留各巡检项的数量，供概览快照复用同一套判定，又不携带样例明细。
     */
    private List<Map<String, Object>> auditCounts(List<Member> members,
        List<MemberGroupVo> groups) {
        var groupNames = groupIdentifiers(groups);
        var summaries = new ArrayList<Map<String, Object>>();
        for (var check : AUDIT_CHECKS) {
            var report = runAudit(check, members, groupNames, 0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("check", check);
            summary.put("count", report.get("count"));
            summaries.add(summary);
        }
        return summaries;
    }

    private Map<String, Object> duplicateAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var field : List.of("qq", "email")) {
            var byValue = members.stream()
                .filter(member -> {
                    var value = auditValue(member, field);
                    return value != null && !value.isBlank();
                })
                .collect(Collectors.groupingBy(
                    member -> auditValue(member, field).toLowerCase(),
                    LinkedHashMap::new,
                    Collectors.toList()));
            for (var entry : byValue.entrySet()) {
                if (entry.getValue().size() < 2) {
                    continue;
                }
                count += entry.getValue().size() - 1;
                if (items.size() < limit) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("field", field);
                    item.put("value", entry.getKey());
                    item.put("members", entry.getValue().stream()
                        .map(member -> member.getMetadata().getName())
                        .toList());
                    items.add(item);
                }
            }
        }
        return auditReport("duplicate", count, items, limit);
    }

    private Map<String, Object> contactAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var spec = member.getSpec();
            String detail = null;
            if (isBlank(spec.getQq())) {
                detail = "missing-qq";
            } else if (isBlank(spec.getQqFriendLink())) {
                detail = "missing-friend-link";
            }
            if (detail == null) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, detail));
            }
        }
        return auditReport("contact", count, items, limit);
    }

    private Map<String, Object> fieldAudit(List<Member> members, int limit, String field) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            if (!isBlank(auditValue(member, field))) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, "missing-" + field));
            }
        }
        return auditReport(field, count, items, limit);
    }

    private Map<String, Object> danglingGroupAudit(List<Member> members, Set<String> groupNames,
        int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var groupName = member.getSpec().getGroupName();
            if (isBlank(groupName) || groupNames.contains(groupName.trim())) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, "group-missing:" + groupName.trim()));
            }
        }
        return auditReport("group", count, items, limit);
    }

    private Map<String, Object> avatarAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var spec = member.getSpec();
            var avatar = spec.getAvatar();
            var expected = AvatarUtils.defaultQqAvatar(spec.getQq());
            String detail = null;
            if (isBlank(avatar)) {
                detail = isBlank(spec.getQq()) ? "missing-avatar" : "avatar-not-derived";
            } else if (expected != null && !expected.equals(avatar.trim())) {
                detail = "avatar-not-derived";
            }
            if (detail == null) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, detail));
            }
        }
        return auditReport("avatar", count, items, limit);
    }

    private Map<String, Object> statusAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var status = member.getSpec().getStatus();
            if (status != null && MEMBER_STATUSES.contains(status.trim())) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, "invalid-status:" + status));
            }
        }
        return auditReport("status", count, items, limit);
    }

    private static Map<String, Object> auditReport(String check, long count,
        List<Map<String, Object>> items, int limit) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("check", check);
        report.put("count", count);
        report.put("truncated", count > items.size());
        report.put("items", items);
        if (count > limit) {
            report.put("hint", "Only the first " + limit
                + " item(s) are listed; raise 'limit' to see more.");
        }
        return report;
    }

    private static Map<String, Object> auditItem(Member member, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", member.getMetadata().getName());
        item.put("displayName", member.getSpec().getDisplayName());
        item.put("status", member.getSpec().getStatus());
        item.put("detail", detail);
        return item;
    }

    private static String auditValue(Member member, String field) {
        var spec = member.getSpec();
        if (spec == null) {
            return null;
        }
        return switch (field) {
            case "qq" -> spec.getQq();
            case "school" -> spec.getSchool();
            default -> spec.getEmail();
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String duplicateValue(MemberVo member, String field) {
        var spec = member.getSpec();
        return switch (field) {
            case "qq" -> spec.getQq();
            case "school" -> spec.getSchool();
            default -> spec.getEmail();
        };
    }

    private McpToolDefinition analyzeSchoolAffinityTool() {
        return McpToolDefinition.builder()
            .name("analyze_school_affinity")
            .title("Analyze school affinity")
            .description("Analyze which member schools are related (cooperation, region or "
                + "shared organizations) and return the links with a strength and a reason. "
                + "This powers the member network view. Requires the site to enable affinity "
                + "analysis and AI Foundation to be available; when disabled the result "
                + "reports enabled=false with a reason instead of failing.")
            .displayTitle("分析学校关联")
            .displayDescription("分析成员学校之间的关联（合作、地域、共同小组织），返回强度与依据；供关系网视图使用。")
            .category(CATEGORY_AFFINITY)
            .inputSchema(objectSchema(Map.of(
                "schools", stringArrayProperty("Explicit school names to analyze. Defaults to "
                    + "every school that has members."),
                "aspects", enumProperty(List.copyOf(SchoolAffinityService.AffinityOptions
                    .ALL_ASPECTS), "Aspects to consider. Defaults to the site setting."),
                "maxLinks", integerProperty("Maximum links to return, 1-40. Defaults to the "
                    + "site setting.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Analyze school affinity"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                final var explicitSchools = optionalNames(args, "schools");
                final var aspectArgs = optionalNames(args, "aspects");
                if (aspectArgs != null) {
                    var unknown = aspectArgs.stream()
                        .filter(aspect -> !SchoolAffinityService.AffinityOptions.ALL_ASPECTS
                            .contains(aspect))
                        .toList();
                    if (!unknown.isEmpty()) {
                        throw invalidArgument("Unknown aspects " + unknown + ", expected "
                            + SchoolAffinityService.AffinityOptions.ALL_ASPECTS + ".");
                    }
                }
                final int maxLinksArg = intArg(args, "maxLinks", 0);
                return settingConfigMember.getBasicConfig().flatMap(config -> {
                    if (!config.isAffinityEnabled()) {
                        return Mono.just(affinityResult(false, "disabled-by-admin", List.of(),
                            null));
                    }
                    var service = affinityServiceProvider.getIfAvailable();
                    if (service == null) {
                        return Mono.just(affinityResult(false, "ai-unavailable", List.of(),
                            null));
                    }
                    var aspects = aspectArgs != null ? aspectArgs
                        : config.effectiveAffinityAspects();
                    var maxLinks = Math.clamp(
                        maxLinksArg > 0 ? maxLinksArg : config.effectiveAffinityMaxLinks(),
                        1, 40);
                    var schools = explicitSchools != null
                        ? Mono.just(explicitSchools.size() > MAX_SCHOOLS
                            ? explicitSchools.subList(0, MAX_SCHOOLS) : explicitSchools)
                        : listMemberSchools();
                    return schools.flatMap(names -> {
                        var meta = affinityMeta(names, aspects, maxLinks);
                        if (names.size() < 2) {
                            return Mono.just(affinityResult(true, "not-enough-schools",
                                List.of(), meta));
                        }
                        var options = new SchoolAffinityService.AffinityOptions(
                            Set.copyOf(aspects), maxLinks);
                        return service.analyze(names, options)
                            .map(links -> affinityResult(true, null, links, meta));
                    });
                });
            }))
            .build();
    }

    private Mono<List<String>> listMemberSchools() {
        return client.list(Member.class, member -> member.getSpec() != null
                && member.getSpec().getSchool() != null
                && !member.getSpec().getSchool().isBlank(), null)
            .map(member -> member.getSpec().getSchool().trim())
            .distinct()
            .take(MAX_SCHOOLS)
            .collectList();
    }

    private static Map<String, Object> affinityMeta(List<String> schools, List<String> aspects,
        int maxLinks) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schoolCount", schools.size());
        meta.put("aspects", aspects);
        meta.put("maxLinks", maxLinks);
        return meta;
    }

    private static McpToolResult affinityResult(boolean enabled, String reason,
        List<SchoolAffinityService.AffinityLink> links, Map<String, Object> meta) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("enabled", enabled);
        content.put("generatedAt", Instant.now().toString());
        if (reason != null) {
            content.put("reason", reason);
        }
        content.put("links", links.stream().map(link -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("a", link.a());
            item.put("b", link.b());
            item.put("strength", link.strength());
            item.put("category", link.category());
            item.put("reason", link.reason());
            return item;
        }).toList());
        if (meta != null) {
            content.put("meta", meta);
        }
        var summary = reason != null ? "Affinity available=" + enabled + ", reason=" + reason
            : links.size() + " affinity link(s)";
        return McpToolResult.success(content, summary);
    }

    private static String annotation(Member member, String key) {
        var annotations = member == null ? null : member.getMetadata().getAnnotations();
        if (annotations == null) {
            return null;
        }
        var value = annotations.get(key);
        return value == null || value.isBlank() ? null : value;
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

    private Map<String, Object> pageContent(ListResult<MemberVo> result, List<String> fields) {
        var items = result.getItems().stream()
            .map(member -> memberSummary(member, fields))
            .toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("page", result.getPage());
        content.put("size", result.getSize());
        content.put("total", result.getTotal());
        content.put("fields", fields);
        content.put("items", items);
        return content;
    }

    private String summary(ListResult<MemberVo> result) {
        return "%d of %d members on page %d".formatted(
            result.getItems().size(), result.getTotal(), result.getPage());
    }

    private Map<String, Object> memberSummary(MemberVo member, List<String> fields) {
        var spec = member.getSpec();
        Map<String, Object> content = new LinkedHashMap<>();
        for (var field : fields) {
            switch (field) {
                case "name" -> content.put("name", member.getMetadata().getName());
                case "displayName" -> content.put("displayName", spec.getDisplayName());
                case "school" -> content.put("school", spec.getSchool());
                case "groupName" -> content.put("groupName", spec.getGroupName());
                default -> content.put("status", spec.getStatus());
            }
        }
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

    /**
     * 归一化并校验 QQ 加好友链接：补/升级为 https，交给 {@link QqLinkUtils} 判官方形态。
     *
     * @return 合法时的规范链接；非法返回空串
     */
    private static String normalizeQqLinkArg(String raw) {
        var value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.regionMatches(true, 0, "http://", 0, 7)) {
            value = "https://" + value.substring(7);
        } else if (!value.contains("://")) {
            value = "https://" + value;
        }
        return QqLinkUtils.validate(value) == null ? value : "";
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
        if (description != null) {
            schema.put("description", description);
        }
        return schema;
    }

    private static Map<String, Object> enumArrayProperty(List<String> values,
        String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", enumProperty(values, null));
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
