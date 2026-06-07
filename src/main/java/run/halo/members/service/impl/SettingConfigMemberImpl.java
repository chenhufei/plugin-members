package run.halo.members.service.impl;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.members.service.SettingConfigMember;

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
        return settingFetcher.fetch(BasicConfig.GROUP, BasicConfig.class)
            .defaultIfEmpty(new BasicConfig());
    }

    @Override
    public Mono<EmailTemplateConfig> getEmailTemplateConfig() {
        return settingFetcher.fetch(EmailTemplateConfig.GROUP, EmailTemplateConfig.class)
            .defaultIfEmpty(new EmailTemplateConfig());
    }
}
