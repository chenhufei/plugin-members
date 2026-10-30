package run.halo.members.mcp;

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

/**
 * 仅在 MCP Server 插件在场时注册成员 MCP 工具 Provider。
 *
 * <p>条件必须使用类名字符串：MCP Server 未安装时本插件类路径没有该 API。
 *
 * @since 1.5.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class MemberMcpConfiguration {

    @Bean
    MemberMcpToolProvider memberMcpToolProvider(RoleService roleService,
        ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService,
        MemberStatisticsService statisticsService,
        MemberWithdrawReviewService withdrawReviewService,
        ClaimTokenService claimTokenService,
        ExternalLinkProcessor externalLinkProcessor) {
        return new MemberMcpToolProvider(roleService, client, memberFinder,
            memberQueryService, memberBatchService, statisticsService, withdrawReviewService,
            claimTokenService, externalLinkProcessor);
    }
}
