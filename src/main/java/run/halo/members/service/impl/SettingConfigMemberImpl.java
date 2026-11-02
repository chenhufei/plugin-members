package run.halo.members.service.impl;

import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.service.SettingConfigMember.AffinityGroupConfig;
import run.halo.members.service.SettingConfigMember.BasicGroupConfig;
import run.halo.members.service.SettingConfigMember.ClaimGroupConfig;
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
                optionalGroup(AffinityGroupConfig.GROUP, AffinityGroupConfig.class),
                optionalGroup(ClaimGroupConfig.GROUP, ClaimGroupConfig.class)
            )
            .map(tuple -> merge(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(),
                tuple.getT5()));
    }

    private <T> Mono<Optional<T>> optionalGroup(String group, Class<T> type) {
        return settingFetcher.fetch(group, type)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    private BasicConfig merge(BasicGroupConfig basic,
                              Optional<NotificationGroupConfig> notification,
                              Optional<SecurityGroupConfig> security,
                              Optional<AffinityGroupConfig> affinity,
                              Optional<ClaimGroupConfig> claim) {
        var config = new BasicConfig();
        config.setAutoApprove(basic.isAutoApprove());
        config.setAutoApproveWithdraw(basic.isAutoApproveWithdraw());
        config.setPendingCapacity(basic.getPendingCapacity());
        config.setDefaultGroupName(basic.getDefaultGroupName());
        config.setForbidSelectedGroupName(basic.getForbidSelectedGroupName());

        var notificationConfig = notification.orElse(null);
        config.setEnableAdminNotification(notificationConfig == null
            || notificationConfig.isEnableAdminNotification());
        config.setAdminUsername(notificationConfig != null
            ? notificationConfig.getAdminUsername() : "admin");
        config.setSendEmail(notificationConfig != null
            ? notificationConfig.isSendEmail() : basic.isSendEmail());
        config.setAdminEmail(notificationConfig != null
            ? notificationConfig.getAdminEmail() : basic.getAdminEmail());

        var securityConfig = security.orElse(null);
        config.setEnableRateLimit(securityConfig != null
            ? securityConfig.isEnableRateLimit() : basic.isEnableRateLimit());
        config.setMaxRequestsPerMinute(securityConfig != null
            ? securityConfig.getMaxRequestsPerMinute() : basic.getMaxRequestsPerMinute());
        config.setEnableSubmitCaptcha(securityConfig != null
            ? securityConfig.isEnableSubmitCaptcha() : basic.isEnableSubmitCaptcha());
        config.setEnableSecurityBlocklist(securityConfig != null
            ? securityConfig.isEnableSecurityBlocklist() : basic.isEnableSecurityBlocklist());
        config.setBlockedIpList(securityConfig != null
            ? securityConfig.getBlockedIpList() : basic.getBlockedIpList());
        config.setBlockedUserAgentKeywords(securityConfig != null
            ? securityConfig.getBlockedUserAgentKeywords() : basic.getBlockedUserAgentKeywords());
        config.setRejectMissingUserAgent(securityConfig != null
            ? securityConfig.isRejectMissingUserAgent() : basic.isRejectMissingUserAgent());
        // 认领专用配置：优先读 claim 组，缺失时回退旧位置（basic），保证升级无损
        var claimConfig = claim.orElse(null);
        config.setAutoApproveClaim(claimConfig != null
            ? claimConfig.isAutoApproveClaim() : basic.isAutoApproveClaim());
        var claimMode = claimConfig == null ? null : claimConfig.getClaimVerifyMode();
        if (claimMode == null || claimMode.isBlank()) {
            claimMode = basic.getClaimVerifyMode();
        }
        // 验证方式必须显式透传，否则 effectiveClaimVerifyMode() 只能走旧字段迁移分支
        config.setClaimVerifyMode(claimMode);
        config.setEnableClaimCaptcha(basic.isEnableClaimCaptcha());
        config.setEnableClaimEmail(basic.isEnableClaimEmail());

        // 学校关联分析：独立设置组，缺省为关闭
        var affinityConfig = affinity.orElse(null);
        config.setAffinityEnabled(affinityConfig != null && affinityConfig.isAffinityEnabled());
        config.setAffinityAspects(affinityConfig == null ? null : affinityConfig.getAffinityAspects());
        config.setAffinityMaxLinks(affinityConfig == null
            ? 12 : affinityConfig.getAffinityMaxLinks());

        return config;
    }
}
