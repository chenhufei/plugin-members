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
 * 成员 MCP 工具 · 统计域：总体统计、概览快照与学校关联分析。
 *
 * <p>概览快照是把「资源读取」折成工具调用的替代方案，返回里带 resource 标识与生成时间。
 *
 * @since 1.5.0
 */
public class MemberMcpStatisticsProvider extends AbstractMemberMcpToolProvider {

    private static final int MAX_SCHOOLS = 40;

    /**
     * 概览快照的分区。mcp-server 1.3.x 的 API 只有 tools，没有 resources，
     * 因此用一次性快照工具替代 {@code members://overview} 这类资源读取。
     */
    private static final List<String> OVERVIEW_SECTIONS = List.of(
        "core", "groups", "schools", "queues", "health", "trend");

    /** 快照资源的稳定标识，供客户端在对话里引用同一份数据。 */
    private static final String OVERVIEW_URI = "members://overview";

    /** 未请求 core 分区时占位，避免为拿一个空壳去调用统计服务。 */
    private static final MemberStatisticsService.OverallStatistics EMPTY_OVERALL_STATISTICS =
        new MemberStatisticsService.OverallStatistics(0, 0, 0, 0, 0, 0.0, null, null);

    private final MemberStatisticsService statisticsService;
    private final SettingConfigMember settingConfigMember;
    private final ObjectProvider<SchoolAffinityService> affinityServiceProvider;

    public MemberMcpStatisticsProvider(RoleService roleService, ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberStatisticsService statisticsService,
        SettingConfigMember settingConfigMember,
        ObjectProvider<SchoolAffinityService> affinityServiceProvider
        ) {
        super(roleService, client, memberFinder);
        this.statisticsService = statisticsService;
        this.settingConfigMember = settingConfigMember;
        this.affinityServiceProvider = affinityServiceProvider;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            getMemberStatisticsTool(),
            getMemberOverviewTool(),
            analyzeSchoolAffinityTool());
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
        var reports = MemberMcpAudit.counts(members, groups);
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
}
