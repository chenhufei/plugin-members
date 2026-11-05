package run.halo.members.mcp;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.members.finders.MemberFinder;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MemberBatchService;
import run.halo.members.service.MemberQueryService;
import run.halo.members.service.MemberStatisticsService;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.service.SchoolAffinityService;
import run.halo.members.service.SettingConfigMember;

/**
 * 仅在 MCP Server 插件在场时注册成员 MCP 工具 Provider。
 *
 * <p>条件必须使用类名字符串：MCP Server 未安装时本插件类路径没有该 API。
 * 工具按域拆成 5 个 Provider（成员档案 / 分组 / 审核 / 认领 / 统计）；
 * mcp-server 的 {@code mcp-tool-provider} 扩展点是 {@code MULTI_INSTANCE}，
 * 多实例会被逐个收集，工具清单与拆分前完全一致。
 *
 * @since 1.5.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class MemberMcpConfiguration {

    @Bean
    MemberMcpQueryProvider memberMcpQueryProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService,
        MemberStatisticsService statisticsService) {
        return new MemberMcpQueryProvider(roleService, client, memberFinder,
            memberQueryService, memberBatchService, statisticsService);
    }

    @Bean
    MemberMcpGroupProvider memberMcpGroupProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService) {
        return new MemberMcpGroupProvider(roleService, client, memberFinder,
            memberQueryService, memberBatchService);
    }

    @Bean
    MemberMcpReviewProvider memberMcpReviewProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberBatchService memberBatchService,
        MemberWithdrawReviewService withdrawReviewService,
        MemberQueryService memberQueryService) {
        return new MemberMcpReviewProvider(roleService, client, memberFinder,
            memberBatchService, withdrawReviewService, memberQueryService);
    }

    @Bean
    MemberMcpClaimProvider memberMcpClaimProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        ClaimTokenService claimTokenService,
        ExternalLinkProcessor externalLinkProcessor) {
        return new MemberMcpClaimProvider(roleService, client, memberFinder,
            memberQueryService, claimTokenService, externalLinkProcessor);
    }

    @Bean
    MemberMcpStatisticsProvider memberMcpStatisticsProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberStatisticsService statisticsService,
        SettingConfigMember settingConfigMember,
        ObjectProvider<SchoolAffinityService> affinityServiceProvider) {
        return new MemberMcpStatisticsProvider(roleService, client, memberFinder,
            statisticsService, settingConfigMember, affinityServiceProvider);
    }
}
