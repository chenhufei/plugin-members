package run.halo.members.mcp;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.members.Member;
import run.halo.members.finders.MemberFinder;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MemberBatchService;
import run.halo.members.service.MemberQueryService;
import run.halo.members.service.MemberStatisticsService;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolResult;

class MemberMcpToolProviderTest {

    private static final Pattern LOCAL_NAME = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

    private static final Set<String> EXPECTED_TOOLS = Set.of(
        "list_members", "get_member", "list_member_groups", "get_member_statistics",
        "review_member_submissions", "review_member_withdrawals", "assign_member_group",
        "update_member", "export_member_data", "batch_import_members",
        "search_members_advanced", "generate_member_report", "member_duplicate_check",
        "auto_approve_pending", "auto_assign_group", "generate_claim_tokens");

    private static final List<String> READ_TOOLS = List.of(
        "list_members", "get_member", "list_member_groups", "get_member_statistics",
        "export_member_data", "search_members_advanced", "generate_member_report",
        "member_duplicate_check");

    private final RoleService roleService = mock(RoleService.class);
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final MemberFinder memberFinder = mock(MemberFinder.class);
    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final MemberBatchService memberBatchService = mock(MemberBatchService.class);
    private final MemberStatisticsService statisticsService = mock(MemberStatisticsService.class);
    private final MemberWithdrawReviewService withdrawReviewService =
        mock(MemberWithdrawReviewService.class);
    private final ClaimTokenService claimTokenService = mock(ClaimTokenService.class);
    private final ExternalLinkProcessor externalLinkProcessor =
        mock(ExternalLinkProcessor.class);

    private final MemberMcpToolProvider provider = new MemberMcpToolProvider(roleService, client,
        memberFinder, memberQueryService, memberBatchService, statisticsService,
        withdrawReviewService, claimTokenService, externalLinkProcessor);

    @BeforeEach
    void mimicExternalUrl() {
        when(externalLinkProcessor.processLink(anyString()))
            .thenAnswer(invocation ->
                "https://example.com" + invocation.getArgument(0, String.class));
    }

    @BeforeEach
    void mimicHaloRoleSemantics() {
        // Halo 的 contains() 会沿角色模板依赖展开，并把超级管理员直接放行。
        when(roleService.contains(any(), any())).thenAnswer(invocation -> {
            Collection<String> granted = invocation.getArgument(0);
            Collection<String> target = invocation.getArgument(1);
            if (granted.contains("super-role")) {
                return Mono.just(true);
            }
            return Mono.just(expandDependencies(granted).containsAll(target));
        });
    }

    private static Set<String> expandDependencies(Collection<String> granted) {
        Set<String> effective = new HashSet<>(granted);
        if (granted.contains(MemberMcpToolProvider.MANAGE_ROLE)) {
            effective.add(MemberMcpToolProvider.VIEW_ROLE);
        }
        return effective;
    }

    @Test
    @DisplayName("工具名与输入 Schema 符合 MCP Server 约束")
    void toolsUseConstrainedNamesAndObjectSchemas() {
        var tools = provider.tools().collectList().block();
        assertThat(tools).isNotNull();

        assertThat(tools).map(McpToolDefinition::name)
            .containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS)
            .doesNotHaveDuplicates();

        for (var tool : tools) {
            assertThat(tool.name()).hasSizeLessThanOrEqualTo(63).matches(LOCAL_NAME);
            assertThat(tool.inputSchema())
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.handler()).isNotNull();
            assertThat(tool.permission()).isNotNull();
            var readOnly = READ_TOOLS.contains(tool.name());
            assertThat(tool.annotations().readOnlyHint()).isEqualTo(readOnly);
        }
    }

    @Test
    @DisplayName("没有登录上下文的调用被拒绝")
    void anonymousCallerIsForbidden() {
        var result = execute("list_members", Map.of(), false);

        assertThat(result).isNotNull();
        assertThat(result.error()).isTrue();
        assertThat(errorCode(result)).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("委托管理员可查看但不能审核")
    void delegatedAdminGetsViewOnly() {
        grantRoles("guest", MemberMcpToolProvider.VIEW_ROLE);

        assertThat(allowed("list_members")).isTrue();
        assertThat(allowed("review_member_submissions")).isFalse();
        assertThat(allowed("assign_member_group")).isFalse();
        assertThat(allowed("auto_approve_pending")).isFalse();
        assertThat(allowed("auto_assign_group")).isFalse();
    }

    @Test
    @DisplayName("拥有管理角色模板即可审核成员申请")
    void memberManagerCanReview() {
        grantRoles(MemberMcpToolProvider.MANAGE_ROLE);

        assertThat(allowed("list_members")).isTrue();
        assertThat(allowed("review_member_submissions")).isTrue();
        assertThat(allowed("assign_member_group")).isTrue();
        assertThat(allowed("auto_approve_pending")).isTrue();
        assertThat(allowed("auto_assign_group")).isTrue();
    }

    @Test
    @DisplayName("超级管理员按角色聚合规则放行")
    void superAdminIsAlwaysAllowed() {
        grantRoles("super-role");

        assertThat(allowed("review_member_submissions")).isTrue();
    }

    @Test
    @DisplayName("缺少成员名称时返回稳定的 INVALID_ARGUMENT")
    void emptyNamesAreRejected() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class,
            () -> execute("review_member_submissions",
                Map.of("names", List.of(), "approved", true), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("未知成员返回 NOT_FOUND 而不是抛异常")
    void unknownMemberIsNotFound() {
        grantRoles("super-role");
        when(client.fetch(Member.class, "ghost")).thenReturn(Mono.empty());

        var result = execute("get_member", Map.of("name", "ghost"), true);

        assertThat(result).isNotNull();
        assertThat(result.error()).isTrue();
        assertThat(errorCode(result)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("高级搜索按创建时间过滤")
    void advancedSearchFiltersByCreatedRange() {
        grantRoles("super-role");
        var early = member("early", "Alice", "alice@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        var late = member("late", "Bob", "bob@example.com",
            Instant.parse("2026-06-01T00:00:00Z"));
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(
            new ListResult<>(1, 20, 2, List.of(MemberVo.from(early), MemberVo.from(late)))));

        var result = execute("search_members_advanced",
            Map.of("createdAfter", "2026-03-01T00:00:00Z"), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("total", 1);
    }

    @Test
    @DisplayName("模糊搜索接受非连续子序列")
    void fuzzyMatchAcceptsSubsequence() {
        grantRoles("super-role");
        var alice = member("m1", "Alice", "alice@example.com",
            Instant.parse("2026-06-01T00:00:00Z"));
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(
            new ListResult<>(1, 20, 1, List.of(MemberVo.from(alice)))));

        var hit = execute("search_members_advanced",
            Map.of("keyword", "ace", "fuzzyMatch", true), true);
        var miss = execute("search_members_advanced",
            Map.of("keyword", "zzz", "fuzzyMatch", true), true);

        assertThat(hit.structuredContent()).containsEntry("total", 1);
        assertThat(miss.structuredContent()).containsEntry("total", 0);
    }

    @Test
    @DisplayName("非法日期参数返回 INVALID_ARGUMENT")
    void invalidDateIsRejected() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class, () -> execute(
            "search_members_advanced", Map.of("createdAfter", "not-a-date"), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("重复检查按邮箱聚类并忽略单例")
    void duplicateCheckClustersSharedEmails() {
        grantRoles("super-role");
        var first = member("a", "A", "same@example.com", Instant.parse("2026-01-01T00:00:00Z"));
        var second = member("b", "B", "SAME@example.com", Instant.parse("2026-01-02T00:00:00Z"));
        var unique = member("c", "C", "other@example.com", Instant.parse("2026-01-03T00:00:00Z"));
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, 20, 3, List.of(MemberVo.from(first), MemberVo.from(second),
                MemberVo.from(unique)))));

        var result = execute("member_duplicate_check", Map.of(), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("clusterCount", 1);
    }

    @Test
    @DisplayName("报告按 format 输出对应内容")
    void reportHonoursFormat() {
        grantRoles("super-role");
        when(statisticsService.getOverallStatistics()).thenReturn(Mono.just(
            new MemberStatisticsService.OverallStatistics(4, 3, 1, 0, 2, 75.0, "A", "S")));

        var result = execute("generate_member_report", Map.of("format", "csv"), true);

        assertThat(result.error()).isFalse();
        assertThat((String) result.structuredContent().get("report"))
            .startsWith("dimension,count");
    }

    @Test
    @DisplayName("修改资料只覆盖传入字段并持久化")
    void updateMemberPersistsProvidedFields() {
        grantRoles("super-role");
        var entity = member("m1", "Alice", "alice@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        entity.getSpec().setSchool("浙江大学");
        when(client.fetch(Member.class, "m1")).thenReturn(Mono.just(entity));
        when(client.update(any())).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        var result = execute("update_member",
            Map.of("name", "m1", "school", "南京大学"), true);

        assertThat(result.error()).isFalse();
        var content = result.structuredContent();
        assertThat(content).containsEntry("school", "南京大学")
            .containsEntry("email", "alice@example.com")
            .containsEntry("displayName", "Alice");
    }

    @Test
    @DisplayName("修改资料未提供任何字段时返回 INVALID_ARGUMENT")
    void updateMemberRequiresOneField() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class,
            () -> execute("update_member", Map.of("name", "m1"), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("撤回审核按成员逐条返回结果")
    void reviewWithdrawalsReportsPerMember() {
        grantRoles("super-role");
        when(withdrawReviewService.review("ok", true)).thenReturn(
            Mono.just(new MemberWithdrawReviewService.ReviewResult(true, "APPROVED", "已下架")));
        when(withdrawReviewService.review("ghost", true)).thenReturn(
            Mono.error(new MemberWithdrawReviewService.MemberNotFoundException("ghost")));

        var result = execute("review_member_withdrawals",
            Map.of("names", List.of("ok", "ghost"), "approved", true), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("total", 2)
            .containsEntry("reviewed", 1L)
            .containsEntry("failed", 1L);
        var items = (List<Map<String, Object>>) result.structuredContent().get("items");
        assertThat(items).extracting(item -> item.get("status"))
            .containsExactly("REVIEWED", "NOT_FOUND");
    }

    @Test
    @DisplayName("自动通过待审核成员默认只预览不修改")
    void autoApprovePendingDefaultsToDryRun() {
        grantRoles("super-role");
        var first = memberWithSchool("p1", "清华大学", "PENDING");
        var second = memberWithSchool("p2", "北京大学", "PENDING");
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, MemberQueryService.MAX_SIZE, 2,
            List.of(MemberVo.from(first), MemberVo.from(second)))));

        var result = execute("auto_approve_pending", Map.of(), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("dryRun", true)
            .containsEntry("pendingScanned", 2L)
            .containsEntry("matched", 2);
        verify(memberBatchService, never()).batchApprove(any(), anyBoolean());
    }

    @Test
    @DisplayName("自动通过待审核成员可按学校筛选并通过")
    void autoApprovePendingAppliesSchoolFilter() {
        grantRoles("super-role");
        var tsinghua = memberWithSchool("p1", "清华大学", "PENDING");
        var peking = memberWithSchool("p2", "北京大学", "PENDING");
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, MemberQueryService.MAX_SIZE, 2,
            List.of(MemberVo.from(tsinghua), MemberVo.from(peking)))));
        when(memberBatchService.batchApprove(any(), anyBoolean())).thenReturn(
            Mono.just(MemberBatchService.BatchOperationResult.success(1)));

        var result = execute("auto_approve_pending",
            Map.of("dryRun", false, "school", "清华"), true);

        assertThat(result.error()).isFalse();
        var content = result.structuredContent();
        assertThat(content).containsEntry("matched", 1);
        assertThat((Map<String, Object>) content.get("result"))
            .containsEntry("success", 1);
        verify(memberBatchService).batchApprove(List.of("p1"), true);
    }

    @Test
    @DisplayName("自动分组拒绝不存在的目标分组")
    void autoAssignGroupRejectsUnknownGroups() {
        grantRoles("super-role");
        when(memberFinder.listAllGroups()).thenReturn(Flux.just(group("union")));

        var error = assertThrows(McpToolException.class, () -> execute("auto_assign_group",
            Map.of("rules", Map.of("清华", "ghost")), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("自动分组默认预览并跳过已有分组的成员")
    void autoAssignGroupDefaultsToDryRun() {
        grantRoles("super-role");
        when(memberFinder.listAllGroups()).thenReturn(Flux.just(group("union")));
        var ungrouped = memberWithSchool("m1", "清华大学", "APPROVED");
        var grouped = memberWithSchool("m2", "清华附中", "APPROVED");
        grouped.getSpec().setGroupName("union");
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, MemberQueryService.MAX_SIZE, 2,
            List.of(MemberVo.from(ungrouped), MemberVo.from(grouped)))));

        var result = execute("auto_assign_group",
            Map.of("rules", Map.of("清华", "union")), true);

        assertThat(result.error()).isFalse();
        var content = result.structuredContent();
        assertThat(content)
            .containsEntry("dryRun", true)
            .containsEntry("matched", 1)
            .containsEntry("planned", 1);
        var assignments = (List<Map<String, Object>>) content.get("assignments");
        assertThat(assignments).extracting(item -> item.get("toGroup"))
            .containsExactly("union");
        verify(memberBatchService, never()).batchChangeGroup(any(), any());
    }

    @Test
    @DisplayName("自动分组不重复移动已在目标分组内的成员")
    void autoAssignGroupSkipsMembersAlreadyInTargetGroup() {
        grantRoles("super-role");
        when(memberFinder.listAllGroups()).thenReturn(Flux.just(group("union")));
        var grouped = memberWithSchool("m2", "清华大学", "APPROVED");
        grouped.getSpec().setGroupName("union");
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, MemberQueryService.MAX_SIZE, 1, List.of(MemberVo.from(grouped)))));

        var result = execute("auto_assign_group",
            Map.of("rules", Map.of("清华", "union"), "onlyUngrouped", false), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("matched", 0);
        verify(memberBatchService, never()).batchChangeGroup(any(), any());
    }

    @Test
    @DisplayName("自动分组 dryRun=false 时按目标分组批量移动")
    void autoAssignGroupAppliesChanges() {
        grantRoles("super-role");
        when(memberFinder.listAllGroups()).thenReturn(Flux.just(group("union")));
        var ungrouped = memberWithSchool("m1", "清华大学", "APPROVED");
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, MemberQueryService.MAX_SIZE, 1, List.of(MemberVo.from(ungrouped)))));
        when(memberBatchService.batchChangeGroup(any(), any())).thenReturn(
            Mono.just(MemberBatchService.BatchOperationResult.success(1)));

        var result = execute("auto_assign_group",
            Map.of("rules", Map.of("清华", "union"), "dryRun", false), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("planned", 1);
        verify(memberBatchService).batchChangeGroup(List.of("m1"), "union");
    }

    @Test
    @DisplayName("生成认领链接按名称列表逐个生成 token")
    void generateClaimTokensByName() {
        grantRoles("super-role");
        when(claimTokenService.generateToken("m1", null)).thenReturn(Mono.just("token-1"));
        when(claimTokenService.generateToken("m2", null)).thenReturn(Mono.just("token-2"));

        var result = execute("generate_claim_tokens",
            Map.of("names", List.of("m1", "m2")),
            true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("generated", 2L);
        var items = (List<Map<String, Object>>) result.structuredContent().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("claimUrl",
            "https://example.com/claim?token=token-1");
    }

    @Test
    @DisplayName("生成认领链接缺少 names 和 autoDetect 时返回 INVALID_ARGUMENT")
    void generateClaimTokensRequiresNamesOrAutoDetect() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class,
            () -> execute("generate_claim_tokens", Map.of(), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("生成认领链接 autoDetect 只选择 QQ 已设但链接为空的成员")
    void generateClaimTokensAutoDetectFiltersMembers() {
        grantRoles("super-role");
        var hasLink = member("m1", "A", "a@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        hasLink.getSpec().setQq("12345");
        hasLink.getSpec().setQqFriendLink("https://link");
        var noLink = member("m2", "B", "b@example.com",
            Instant.parse("2026-01-02T00:00:00Z"));
        noLink.getSpec().setQq("67890");
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(new ListResult<>(
            1, MemberQueryService.MAX_SIZE, 2,
            List.of(MemberVo.from(hasLink), MemberVo.from(noLink)))));
        when(claimTokenService.generateToken("m2", null)).thenReturn(Mono.just("tok-2"));

        var result = execute("generate_claim_tokens",
            Map.of("autoDetect", true), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("generated", 1L);
        verify(claimTokenService).generateToken("m2", null);
    }

    private static Member memberWithSchool(String name, String school, String status) {
        var member = member(name, name.toUpperCase(), name + "@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        member.getSpec().setSchool(school);
        member.getSpec().setStatus(status);
        return member;
    }

    private static MemberGroupVo group(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return MemberGroupVo.builder().metadata(metadata).build();
    }

    private static Member member(String name, String displayName, String email,
        Instant createdAt) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(createdAt);
        var spec = new Member.MemberSpec();
        spec.setDisplayName(displayName);
        spec.setEmail(email);
        spec.setStatus("APPROVED");
        var member = new Member();
        member.setMetadata(metadata);
        member.setSpec(spec);
        return member;
    }

    private void grantRoles(String... roles) {
        when(roleService.getRolesByUsername(any())).thenReturn(Flux.fromArray(roles));
    }

    private boolean allowed(String toolName) {
        return Boolean.TRUE.equals(tool(toolName).permission()
            .check(new McpToolInvocation(toolName, Map.of()))
            .contextWrite(authenticated())
            .block());
    }

    private McpToolResult execute(String toolName, Map<String, Object> arguments,
        boolean authenticated) {
        var handler = tool(toolName).handler().execute(
            new McpToolInvocation(toolName, arguments));
        return (authenticated ? handler.contextWrite(authenticated()) : handler).block();
    }

    private static Context authenticated() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(
            "memberadmin", "n/a", "ROLE_USER"));
        return ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context));
    }

    private static Object errorCode(McpToolResult result) {
        return ((Map<String, ?>) result.structuredContent().get("error")).get("code");
    }

    private McpToolDefinition tool(String name) {
        return provider.tools()
            .filter(definition -> name.equals(definition.name()))
            .next()
            .blockOptional()
            .orElseThrow(() -> new AssertionError("Missing MCP tool " + name));
    }
}
