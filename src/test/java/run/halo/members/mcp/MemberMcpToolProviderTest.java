package run.halo.members.mcp;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        "auto_approve_pending", "auto_assign_group", "generate_claim_tokens",
        "create_member_group", "update_member_group", "delete_member_group",
        "list_member_withdrawals", "analyze_school_affinity", "list_claim_tokens",
        "revoke_claim_token", "validate_qq_link", "audit_member_data",
        "batch_update_members", "get_member_overview");

    private static final List<String> READ_TOOLS = List.of(
        "list_members", "get_member", "list_member_groups", "get_member_statistics",
        "export_member_data", "search_members_advanced", "generate_member_report",
        "member_duplicate_check", "list_member_withdrawals", "analyze_school_affinity",
        "list_claim_tokens", "validate_qq_link", "audit_member_data",
        "get_member_overview");

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
    private final SettingConfigMember settingConfigMember = mock(SettingConfigMember.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SchoolAffinityService> affinityServiceProvider =
        mock(ObjectProvider.class);

    private final MemberMcpToolProvider provider = new MemberMcpToolProvider(roleService, client,
        memberFinder, memberQueryService, memberBatchService, statisticsService,
        withdrawReviewService, claimTokenService, externalLinkProcessor, settingConfigMember,
        affinityServiceProvider);

    @BeforeEach
    void mimicExternalUrl() {
        when(externalLinkProcessor.processLink(anyString()))
            .thenAnswer(invocation ->
                "https://example.com" + invocation.getArgument(0, String.class));
    }

    @BeforeEach
    void mimicSettings() {
        when(settingConfigMember.getBasicConfig())
            .thenReturn(Mono.just(new SettingConfigMember.BasicConfig()));
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

    @Test
    @DisplayName("新建分组拒绝重名")
    void createGroupRejectsDuplicateName() {
        grantRoles("super-role");
        when(memberFinder.listAllGroups())
            .thenReturn(Flux.just(existingGroup("union", "高校联盟")));

        var result = execute("create_member_group", Map.of("displayName", "高校联盟"), true);

        assertThat(result.error()).isTrue();
        assertThat(errorCode(result)).isEqualTo("GROUP_EXISTS");
        verify(client, never()).create(any());
    }

    @Test
    @DisplayName("新建分组按名称生成 metadata.name")
    void createGroupGeneratesName() {
        grantRoles("super-role");
        when(memberFinder.listAllGroups()).thenReturn(Flux.empty());
        when(client.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var result = execute("create_member_group",
            Map.of("displayName", "Campus Media", "description", "校媒"), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("displayName", "Campus Media")
            .containsEntry("description", "校媒");
        assertThat((String) result.structuredContent().get("name")).startsWith("campus-media-");
    }

    @Test
    @DisplayName("删除分组时仍有成员则拒绝")
    void deleteGroupRequiresEmptyGroup() {
        grantRoles("super-role");
        when(client.fetch(MemberGroup.class, "union"))
            .thenReturn(Mono.just(groupEntity("union", "高校联盟")));
        var member = memberWithSchool("m1", "清华大学", "APPROVED");
        member.getSpec().setGroupName("高校联盟");
        stubMembers(List.of(member));

        var result = execute("delete_member_group", Map.of("name", "union"), true);

        assertThat(result.error()).isTrue();
        assertThat(errorCode(result)).isEqualTo("GROUP_NOT_EMPTY");
        verify(client, never()).delete(any());
    }

    @Test
    @DisplayName("分组重命名同步更新成员所属分组")
    void renamingGroupMovesMembers() {
        grantRoles("super-role");
        when(client.fetch(MemberGroup.class, "union"))
            .thenReturn(Mono.just(groupEntity("union", "高校联盟")));
        when(client.update(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var member = memberWithSchool("m1", "清华大学", "APPROVED");
        member.getSpec().setGroupName("高校联盟");
        stubMembers(List.of(member));

        var result = execute("update_member_group",
            Map.of("name", "union", "displayName", "全国高校联盟"), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("displayName", "全国高校联盟")
            .containsEntry("renamedFrom", "高校联盟")
            .containsEntry("membersMoved", 1L);
        assertThat(member.getSpec().getGroupName()).isEqualTo("全国高校联盟");
    }

    @Test
    @DisplayName("撤回申请列表带出理由与撤回前状态")
    void withdrawalListExposesReason() {
        grantRoles("super-role");
        var pending = memberWithSchool("w1", "清华大学", "WITHDRAW_REQUESTED");
        var annotations = new java.util.HashMap<String, String>();
        annotations.put(MemberConstant.WITHDRAW_REASON, "已毕业");
        annotations.put(MemberConstant.WITHDRAW_STATUS_BEFORE, "APPROVED");
        pending.getMetadata().setAnnotations(annotations);
        var approved = memberWithSchool("a1", "北京大学", "APPROVED");
        stubMembers(List.of(pending, approved));

        var result = execute("list_member_withdrawals", Map.of(), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("pending", 1);
        var items = (List<Map<String, Object>>) result.structuredContent().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0))
            .containsEntry("name", "w1")
            .containsEntry("reason", "已毕业")
            .containsEntry("statusBefore", "APPROVED");
    }

    @Test
    @DisplayName("后台关闭关联分析时如实返回 disabled-by-admin")
    void affinityReportsDisabledByAdmin() {
        grantRoles("super-role");
        var config = new SettingConfigMember.BasicConfig();
        config.setAffinityEnabled(false);
        when(settingConfigMember.getBasicConfig()).thenReturn(Mono.just(config));

        var result = execute("analyze_school_affinity",
            Map.of("schools", List.of("清华大学", "北京大学")), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("enabled", false)
            .containsEntry("reason", "disabled-by-admin");
    }

    @Test
    @DisplayName("安装 AI 后返回学校关联与依据")
    void affinityReturnsLinks() {
        grantRoles("super-role");
        var config = new SettingConfigMember.BasicConfig();
        config.setAffinityEnabled(true);
        when(settingConfigMember.getBasicConfig()).thenReturn(Mono.just(config));
        SchoolAffinityService service = mock(SchoolAffinityService.class);
        when(affinityServiceProvider.getIfAvailable()).thenReturn(service);
        when(service.analyze(any(), any())).thenReturn(Mono.just(List.of(
            new SchoolAffinityService.AffinityLink("清华大学", "北京大学", 0.82, "region",
                "同城"))));

        var result = execute("analyze_school_affinity",
            Map.of("schools", List.of("清华大学", "北京大学"), "maxLinks", 5), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("enabled", true);
        var links = (List<Map<String, Object>>) result.structuredContent().get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0))
            .containsEntry("a", "清华大学")
            .containsEntry("strength", 0.82)
            .containsEntry("reason", "同城");
    }

    @Test
    @DisplayName("作废认领令牌区分已作废与无令牌")
    void revokeClaimTokenReportsStatus() {
        grantRoles("super-role");
        var withToken = member("t1", "A", "a@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        var tokenAnnotations = new java.util.HashMap<String, String>();
        tokenAnnotations.put(ClaimTokenService.CLAIM_TOKEN_ANNOTATION, "tok");
        withToken.getMetadata().setAnnotations(tokenAnnotations);
        var withoutToken = member("t2", "B", "b@example.com",
            Instant.parse("2026-01-02T00:00:00Z"));
        when(client.fetch(Member.class, "t1")).thenReturn(Mono.just(withToken));
        when(client.fetch(Member.class, "t2")).thenReturn(Mono.just(withoutToken));
        when(claimTokenService.invalidateToken("t1")).thenReturn(Mono.just(withToken));

        var result = execute("revoke_claim_token",
            Map.of("names", List.of("t1", "t2")), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("revoked", 1L);
        var items = (List<Map<String, Object>>) result.structuredContent().get("items");
        assertThat(items).extracting(item -> item.get("status"))
            .containsExactly("REVOKED", "NONE");
    }

    @Test
    @DisplayName("QQ 链接校验接受两种官方形态并拒绝其他站点")
    void qqLinkValidationCoversBothOfficialForms() {
        grantRoles("super-role");

        var shortLink = execute("validate_qq_link",
            Map.of("link", "qm.qq.com/q/abc123"), true);
        var legacyLink = execute("validate_qq_link",
            Map.of("link", "https://qm.qq.com/cgi-bin/qm/qr?k=xyz"), true);
        var phishing = execute("validate_qq_link",
            Map.of("link", "https://evil.example.com/q/abc123"), true);

        assertThat(shortLink.structuredContent())
            .containsEntry("valid", true)
            .containsEntry("link", "https://qm.qq.com/q/abc123");
        assertThat(legacyLink.structuredContent()).containsEntry("valid", true);
        assertThat(phishing.structuredContent()).containsEntry("valid", false);
    }

    @Test
    @DisplayName("数据巡检汇总重复、缺失与失配问题")
    void auditReportsDataQualityIssues() {
        grantRoles("super-role");
        var first = member("a1", "A", "dup@example.com", Instant.parse("2026-01-01T00:00:00Z"));
        first.getSpec().setQq("12345");
        first.getSpec().setGroupName("已删除的分组");
        var second = member("a2", "B", "DUP@example.com",
            Instant.parse("2026-01-02T00:00:00Z"));
        second.getSpec().setQq("12345");
        second.getSpec().setQqFriendLink("https://qm.qq.com/q/abc");
        second.getSpec().setAvatar(AvatarUtils.defaultQqAvatar("12345"));
        stubMembers(List.of(first, second));
        when(memberFinder.listAllGroups())
            .thenReturn(Flux.just(existingGroup("union", "高校联盟")));

        var result = execute("audit_member_data", Map.of(), true);

        assertThat(result.error()).isFalse();
        var content = result.structuredContent();
        assertThat(content).containsEntry("scanned", 2).containsEntry("healthy", false);
        var checks = (List<Map<String, Object>>) content.get("checks");
        assertThat(countOf(checks, "duplicate")).isEqualTo(2L);
        assertThat(countOf(checks, "contact")).isEqualTo(1L);
        assertThat(countOf(checks, "school")).isEqualTo(2L);
        assertThat(countOf(checks, "group")).isEqualTo(1L);
        assertThat(countOf(checks, "avatar")).isEqualTo(1L);
        assertThat(countOf(checks, "status")).isZero();
    }

    @Test
    @DisplayName("数据巡检对干净数据报告 healthy")
    void auditReportsHealthyData() {
        grantRoles("super-role");
        var clean = member("c1", "C", "c@example.com", Instant.parse("2026-03-01T00:00:00Z"));
        clean.getSpec().setQq("67890");
        clean.getSpec().setSchool("清华大学");
        clean.getSpec().setQqFriendLink("https://qm.qq.com/q/abc");
        clean.getSpec().setGroupName("高校联盟");
        clean.getSpec().setAvatar(AvatarUtils.defaultQqAvatar("67890"));
        stubMembers(List.of(clean));
        when(memberFinder.listAllGroups())
            .thenReturn(Flux.just(existingGroup("union", "高校联盟")));

        var result = execute("audit_member_data", Map.of("checks", List.of("contact", "avatar")),
            true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("healthy", true)
            .containsEntry("issueTotal", 0L);
    }

    @Test
    @DisplayName("数据巡检按分组资源名归属时不得误报分组缺失")
    void auditTreatsGroupResourceNameAsValidGroup() {
        grantRoles("super-role");
        // 线上真实形态：成员的 groupName 存的是分组资源名，不是显示名
        var byResourceName = member("g1", "G", "g@example.com", Instant.parse("2026-03-01T00:00:00Z"));
        byResourceName.getSpec().setQq("67891");
        byResourceName.getSpec().setSchool("重庆市大足一中");
        byResourceName.getSpec().setQqFriendLink("https://qm.qq.com/q/abc");
        byResourceName.getSpec().setGroupName("member-group-buuuwxaw");
        byResourceName.getSpec().setAvatar(AvatarUtils.defaultQqAvatar("67891"));
        stubMembers(List.of(byResourceName));
        when(memberFinder.listAllGroups())
            .thenReturn(Flux.just(existingGroup("member-group-buuuwxaw", "重庆地区")));

        var result = execute("audit_member_data", Map.of("checks", List.of("group")), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("issueTotal", 0L)
            .containsEntry("healthy", true);
    }

    @Test
    @DisplayName("数据巡检拒绝未知检查项")
    void auditRejectsUnknownCheck() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class, () -> execute("audit_member_data",
            Map.of("checks", List.of("magic")), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("批量修改默认只预览不写库")
    void batchUpdateDefaultsToDryRun() {
        grantRoles("super-role");
        var member = memberWithSchool("m1", "清华大学", "APPROVED");
        when(client.fetch(Member.class, "m1")).thenReturn(Mono.just(member));

        var result = execute("batch_update_members",
            Map.of("names", List.of("m1"), "school", "北京大学"), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("dryRun", true)
            .containsEntry("updated", 1L);
        assertThat(member.getSpec().getSchool()).isEqualTo("清华大学");
        verify(client, never()).update(any());
    }

    @Test
    @DisplayName("批量修改落库并支持清空分组")
    void batchUpdateAppliesAndClearsGroup() {
        grantRoles("super-role");
        var member = memberWithSchool("m1", "清华大学", "APPROVED");
        member.getSpec().setGroupName("高校联盟");
        when(client.fetch(Member.class, "m1")).thenReturn(Mono.just(member));
        when(client.update(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var result = execute("batch_update_members", Map.of("names", List.of("m1"),
            "school", "北京大学", "clearGroup", true, "dryRun", false), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("updated", 1L);
        assertThat(member.getSpec().getSchool()).isEqualTo("北京大学");
        assertThat(member.getSpec().getGroupName()).isNull();
    }

    @Test
    @DisplayName("批量修改拒绝非官方 QQ 链接")
    void batchUpdateRejectsForeignFriendLink() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class, () -> execute("batch_update_members",
            Map.of("names", List.of("m1"), "qqFriendLink", "https://evil.com/q/abc"), true));

        assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("修改资料可清空链接并归一化加入时间")
    void updateMemberClearsFriendLinkAndNormalizesJoinedAt() {
        grantRoles("super-role");
        var entity = member("m1", "Alice", "alice@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        entity.getSpec().setQqFriendLink("https://qm.qq.com/q/old");
        when(client.fetch(Member.class, "m1")).thenReturn(Mono.just(entity));
        when(client.update(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var result = execute("update_member",
            Map.of("name", "m1", "qqFriendLink", "", "joinedAt", "2026-03-01"), true);

        assertThat(result.error()).isFalse();
        assertThat(entity.getSpec().getQqFriendLink()).isNull();
        assertThat(entity.getSpec().getJoinedAt()).isEqualTo("2026-03-01T00:00:00Z");
    }

    @Test
    @DisplayName("修改资料接受二维码形态的官方链接")
    void updateMemberAcceptsQrEncodedLink() {
        grantRoles("super-role");
        var entity = member("m1", "Alice", "alice@example.com",
            Instant.parse("2026-01-01T00:00:00Z"));
        when(client.fetch(Member.class, "m1")).thenReturn(Mono.just(entity));
        when(client.update(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var result = execute("update_member", Map.of("name", "m1", "qqFriendLink",
            "qm.qq.com/cgi-bin/qm/qr?k=abc&jump_from=webapi"), true);

        assertThat(result.error()).isFalse();
        assertThat(entity.getSpec().getQqFriendLink())
            .isEqualTo("https://qm.qq.com/cgi-bin/qm/qr?k=abc&jump_from=webapi");
    }

    @Test
    @DisplayName("概览快照一次返回全部分区")
    void overviewSnapshotCoversEverySection() {
        grantRoles("super-role");
        var approved = member("m1", "甲", "m1@example.com",
            Instant.parse("2026-10-01T00:00:00Z"));
        approved.getSpec().setQq("10001");
        approved.getSpec().setSchool("清华大学");
        approved.getSpec().setQqFriendLink("https://qm.qq.com/q/abc");
        approved.getSpec().setGroupName("高校联盟");
        approved.getSpec().setAvatar(AvatarUtils.defaultQqAvatar("10001"));
        var pending = member("m2", "乙", "m2@example.com",
            Instant.parse("2026-10-02T00:00:00Z"));
        pending.getSpec().setStatus("PENDING");
        var withdrawing = member("m3", "丙", "m3@example.com",
            Instant.parse("2026-10-03T00:00:00Z"));
        withdrawing.getSpec().setStatus("WITHDRAW_REQUESTED");
        stubMembers(List.of(approved, pending, withdrawing));
        when(memberFinder.listAllGroups()).thenReturn(
            Flux.just(existingGroup("union", "高校联盟"), existingGroup("spare", "空分组")));
        when(statisticsService.getOverallStatistics()).thenReturn(Mono.just(
            new MemberStatisticsService.OverallStatistics(3, 1, 1, 0, 2, 100.0,
                "高校联盟", "清华大学")));
        when(statisticsService.getGroupStatistics())
            .thenReturn(Mono.just(Map.of("高校联盟", 1)));
        when(statisticsService.getSchoolStatistics())
            .thenReturn(Mono.just(Map.of("清华大学", 2, "北京大学", 1)));
        when(statisticsService.getApplicationTrend(14))
            .thenReturn(Mono.just(Map.of("2026-10-01", 2)));

        var result = execute("get_member_overview", Map.of(), true);

        assertThat(result.error()).isFalse();
        var content = result.structuredContent();
        assertThat(content).containsEntry("resource", "members://overview");
        assertThat(content.get("generatedAt")).isNotNull();
        assertThat((Map<String, Object>) content.get("core"))
            .containsEntry("totalMembers", 3)
            .containsEntry("approvalRate", 100.0);

        // 未分组排在首位，只定义未使用的分组也要出现，方便发现空分组
        var groups = (List<Map<String, Object>>) content.get("groups");
        assertThat(groups).hasSize(3)
            .first()
            .satisfies(item -> assertThat(item).containsEntry("name", "（未分组）")
                .containsEntry("count", 2));
        assertThat(groups).anySatisfy(item -> assertThat(item)
            .containsEntry("name", "空分组")
            .containsEntry("count", 0));

        assertThat((Map<String, Object>) content.get("queues"))
            .containsEntry("pendingApplications", 1L)
            .containsEntry("withdrawRequests", 1L)
            .containsEntry("approvedWithoutGroup", 0L);

        var health = (Map<String, Object>) content.get("health");
        assertThat(health).containsEntry("scanned", 3).containsEntry("healthy", false);
        var checks = (List<Map<String, Object>>) health.get("checks");
        assertThat(countOf(checks, "contact")).isEqualTo(2L);
        assertThat(countOf(checks, "school")).isEqualTo(2L);

        var trend = (Map<String, Object>) content.get("trend");
        assertThat((List<Map<String, Object>>) trend.get("items"))
            .singleElement()
            .satisfies(item -> assertThat(item)
                .containsEntry("name", "2026-10-01")
                .containsEntry("count", 2));
    }

    @Test
    @DisplayName("概览快照可用 sections 裁剪返回内容")
    void overviewHonoursSectionFilter() {
        grantRoles("super-role");
        stubMembers(List.of(member("m1", "甲", "m1@example.com",
            Instant.parse("2026-10-01T00:00:00Z"))));
        when(memberFinder.listAllGroups())
            .thenReturn(Flux.just(existingGroup("union", "高校联盟")));
        when(statisticsService.getOverallStatistics()).thenReturn(Mono.just(
            new MemberStatisticsService.OverallStatistics(1, 1, 0, 0, 1, 100.0,
                "高校联盟", "清华大学")));

        var result = execute("get_member_overview", Map.of("sections", List.of("core")), true);

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsOnlyKeys("resource", "generatedAt", "sections", "core");
    }

    @Test
    @DisplayName("概览快照拒绝未知分区")
    void overviewRejectsUnknownSection() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class, () -> execute("get_member_overview",
            Map.of("sections", List.of("nope")), true));

        assertThat(error.getMessage()).contains("nope");
    }

    @Test
    @DisplayName("成员列表可按 fields 裁剪返回字段")
    void listMembersProjectsRequestedFields() {
        grantRoles("super-role");
        var alice = member("m1", "Alice", "alice@example.com",
            Instant.parse("2026-06-01T00:00:00Z"));
        when(memberQueryService.listMembers(any())).thenReturn(Mono.just(
            new ListResult<>(1, 20, 1, List.of(MemberVo.from(alice)))));

        var result = execute("list_members",
            Map.of("fields", List.of("name", "status")), true);

        assertThat(result.error()).isFalse();
        var content = result.structuredContent();
        assertThat((List<String>) content.get("fields")).containsExactly("name", "status");
        var items = (List<Map<String, Object>>) content.get("items");
        assertThat(items).singleElement()
            .satisfies(item -> assertThat(item)
                .containsOnlyKeys("name", "status")
                .containsEntry("name", "m1")
                .containsEntry("status", "APPROVED"));
    }

    @Test
    @DisplayName("成员列表拒绝未知 fields")
    void listMembersRejectsUnknownField() {
        grantRoles("super-role");

        var error = assertThrows(McpToolException.class, () -> execute("list_members",
            Map.of("fields", List.of("email")), true));

        assertThat(error.getMessage()).contains("email");
    }

    private static long countOf(List<Map<String, Object>> checks, String name) {
        return checks.stream()
            .filter(check -> name.equals(check.get("check")))
            .map(check -> (Long) check.get("count"))
            .findFirst()
            .orElse(-1L);
    }

    @SuppressWarnings("unchecked")
    private void stubMembers(List<Member> members) {
        when(client.list(eq(Member.class), any(), any())).thenAnswer(invocation -> {
            Predicate<Member> predicate = invocation.getArgument(1);
            return Flux.fromIterable(members).filter(predicate);
        });
    }

    private static MemberGroupVo existingGroup(String name, String displayName) {
        var metadata = new Metadata();
        metadata.setName(name);
        var spec = new MemberGroup.MemberGroupSpec();
        spec.setDisplayName(displayName);
        return MemberGroupVo.builder().metadata(metadata).spec(spec).build();
    }

    private static MemberGroup groupEntity(String name, String displayName) {
        var metadata = new Metadata();
        metadata.setName(name);
        var spec = new MemberGroup.MemberGroupSpec();
        spec.setDisplayName(displayName);
        var group = new MemberGroup();
        group.setMetadata(metadata);
        group.setSpec(spec);
        return group;
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
