package run.halo.members.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 成员 MCP 工具 · 成员档案域：列表/详情、资料修改、导入导出、检索、报告、巡检。
 *
 * <p>不涉及审核动作与分组归属；写操作只改成员自身字段，删除与合并依旧不暴露。
 *
 * @since 1.5.0
 */
public class MemberMcpQueryProvider extends AbstractMemberMcpToolProvider {

    private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper();


    private final MemberQueryService memberQueryService;
    private final MemberBatchService memberBatchService;
    private final MemberStatisticsService statisticsService;

    public MemberMcpQueryProvider(RoleService roleService, ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService,
        MemberStatisticsService statisticsService
        ) {
        super(roleService, client, memberFinder);
        this.memberQueryService = memberQueryService;
        this.memberBatchService = memberBatchService;
        this.statisticsService = statisticsService;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            listMembersTool(),
            getMemberTool(),
            updateMemberTool(),
            exportMemberDataTool(),
            batchImportMembersTool(),
            batchUpdateMembersTool(),
            searchMembersAdvancedTool(),
            generateMemberReportTool(),
            memberDuplicateCheckTool(),
            auditMemberDataTool());
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
                                String value = MemberMcpAudit.duplicateValue(member, field);
                                return value != null && !value.isBlank();
                            })
                            .collect(Collectors.groupingBy(
                                member -> MemberMcpAudit.duplicateValue(member, field).toLowerCase(),
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
                "checks", enumArrayProperty(List.copyOf(MemberMcpAudit.CHECKS),
                    "Checks to run. Defaults to all of them."),
                "limit", integerProperty("Maximum sample items per check. 1-100, default 20.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("Audit member data"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var requested = optionalNames(args, "checks");
                final var checks = requested != null ? requested : MemberMcpAudit.CHECKS;
                var unknown = checks.stream()
                    .filter(check -> !MemberMcpAudit.CHECKS.contains(check))
                    .toList();
                if (!unknown.isEmpty()) {
                    throw invalidArgument("Unknown checks " + unknown + ", expected "
                        + MemberMcpAudit.CHECKS + ".");
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
        var groupNames = MemberMcpAudit.groupIdentifiers(groups);
        var reports = new ArrayList<Map<String, Object>>();
        long issueTotal = 0;
        for (var check : checks) {
            var report = MemberMcpAudit.run(check, members, groupNames, limit);
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

}
