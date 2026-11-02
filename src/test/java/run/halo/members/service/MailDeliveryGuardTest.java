package run.halo.members.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.members.service.SettingConfigMember.BasicConfig;

/**
 * 发信前置自检：邮件没配好时必须给出可操作的原因，配好了才放行。
 */
@ExtendWith(MockitoExtension.class)
class MailDeliveryGuardTest {

    @Mock
    private ReactiveExtensionClient client;
    @Mock
    private SettingConfigMember settingConfigMember;

    private MailDeliveryGuard guard() {
        return new MailDeliveryGuard(client, settingConfigMember);
    }

    private void pluginSwitch(boolean on) {
        BasicConfig config = new BasicConfig();
        config.setSendEmail(on);
        when(settingConfigMember.getBasicConfig()).thenReturn(Mono.just(config));
    }

    private void notifier(String senderJson) {
        Secret secret = new Secret();
        secret.setStringData(java.util.Map.of(MailDeliveryGuard.EMAIL_NOTIFIER_KEY, senderJson));
        when(client.fetch(Secret.class, MailDeliveryGuard.NOTIFIER_SECRET))
            .thenReturn(Mono.just(secret));
    }

    private Optional<String> reason(MailDeliveryGuard guard) {
        return guard.unavailabilityReason().block();
    }

    @Test
    @DisplayName("插件开关关闭时提示去插件设置打开")
    void pluginSwitchOff() {
        pluginSwitch(false);

        assertThat(reason(guard())).hasValueSatisfying(
            message -> assertThat(message).contains("插件设置").contains("发送邮件通知"));
    }

    @Test
    @DisplayName("没有通知器配置时提示去系统设置配置 SMTP")
    void notifierMissing() {
        pluginSwitch(true);
        when(client.fetch(Secret.class, MailDeliveryGuard.NOTIFIER_SECRET)).thenReturn(Mono.empty());

        assertThat(reason(guard())).hasValueSatisfying(
            message -> assertThat(message).contains("系统 → 通知 → 邮件通知").contains("SMTP"));
    }

    @Test
    @DisplayName("通知器未启用时提示先勾选启用")
    void notifierDisabled() {
        pluginSwitch(true);
        notifier("{\"sender\":{\"enable\":false,\"host\":\"smtp.example.com\","
            + "\"sender\":\"noreply@example.com\"}}");

        assertThat(reason(guard())).hasValueSatisfying(
            message -> assertThat(message).contains("启用邮件通知器"));
    }

    @Test
    @DisplayName("缺主机或发信地址时提示补全")
    void notifierIncomplete() {
        pluginSwitch(true);
        notifier("{\"sender\":{\"enable\":true,\"host\":\"\",\"sender\":\"\"}}");

        assertThat(reason(guard())).hasValueSatisfying(
            message -> assertThat(message).contains("补全"));
    }

    @Test
    @DisplayName("用户名可作为发信地址，配置齐全时不拦截")
    void fullyConfigured() {
        pluginSwitch(true);
        notifier("{\"sender\":{\"enable\":true,\"host\":\"smtp.example.com\","
            + "\"username\":\"noreply@example.com\",\"sender\":\"\"}}");

        assertThat(reason(guard())).isEmpty();
    }

    @Test
    @DisplayName("读不到配置时不阻断管理员操作")
    void readFailureDoesNotBlock() {
        pluginSwitch(true);
        when(client.fetch(Secret.class, MailDeliveryGuard.NOTIFIER_SECRET))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        assertThat(reason(guard())).isEmpty();
    }
}
