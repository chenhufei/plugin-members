package run.halo.members.service.impl;

import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.service.SettingConfigMember.BasicGroupConfig;
import run.halo.members.service.SettingConfigMember.IntegrationGroupConfig;
import run.halo.members.service.SettingConfigMember.NotificationGroupConfig;
import run.halo.members.service.SettingConfigMember.SecurityGroupConfig;

/**
 * 成员管理插件配置服务实现
 * @since 1.0.34
 */
@Component
@RequiredArgsConstructor
public class SettingConfigMemberImpl implements SettingConfigMember {

    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public Mono<BasicConfig> getBasicConfig() {
        return Mono.zip(
                settingFetcher.fetch(BasicGroupConfig.GROUP, BasicGroupConfig.class)
                    .defaultIfEmpty(new BasicGroupConfig()),
                optionalGroup(NotificationGroupConfig.GROUP, NotificationGroupConfig.class),
                optionalGroup(SecurityGroupConfig.GROUP, SecurityGroupConfig.class),
                optionalGroup(IntegrationGroupConfig.GROUP, IntegrationGroupConfig.class)
            )
            .map(tuple -> merge(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()));
    }

    private <T> Mono<Optional<T>> optionalGroup(String group, Class<T> type) {
        return settingFetcher.fetch(group, type)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    private BasicConfig merge(BasicGroupConfig basic,
                              Optional<NotificationGroupConfig> notification,
                              Optional<SecurityGroupConfig> security,
                              Optional<IntegrationGroupConfig> integration) {
        var config = new BasicConfig();
        config.setAutoApprove(basic.isAutoApprove());
        config.setAutoApproveWithdraw(basic.isAutoApproveWithdraw());
        config.setDefaultGroupName(basic.getDefaultGroupName());
        config.setForbidSelectedGroupName(basic.getForbidSelectedGroupName());

        var notificationConfig = notification.orElse(null);
        config.setSendEmail(notificationConfig != null
            ? notificationConfig.isSendEmail() : basic.isSendEmail());
        config.setAdminEmail(notificationConfig != null
            ? notificationConfig.getAdminEmail() : basic.getAdminEmail());

        var securityConfig = security.orElse(null);
        config.setEnableRateLimit(securityConfig != null
            ? securityConfig.isEnableRateLimit() : basic.isEnableRateLimit());
        config.setMaxRequestsPerMinute(securityConfig != null
            ? securityConfig.getMaxRequestsPerMinute() : basic.getMaxRequestsPerMinute());
        config.setEnableSecurityBlocklist(securityConfig != null
            ? securityConfig.isEnableSecurityBlocklist() : basic.isEnableSecurityBlocklist());
        config.setBlockedIpList(securityConfig != null
            ? securityConfig.getBlockedIpList() : basic.getBlockedIpList());
        config.setBlockedUserAgentKeywords(securityConfig != null
            ? securityConfig.getBlockedUserAgentKeywords() : basic.getBlockedUserAgentKeywords());
        config.setRejectMissingUserAgent(securityConfig != null
            ? securityConfig.isRejectMissingUserAgent() : basic.isRejectMissingUserAgent());

        var integrationConfig = integration.orElse(null);
        config.setUapisToken(integrationConfig != null
            ? integrationConfig.getUapisToken() : basic.getUapisToken());
        return config;
    }
}
