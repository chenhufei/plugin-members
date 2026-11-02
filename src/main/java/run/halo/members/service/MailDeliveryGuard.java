package run.halo.members.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/**
 * 发信前的站点邮件配置自检。
 *
 * <p>验证码与认领链接都由 Halo 通知中心的邮件通知器投递，插件只能保证「通知已提交」。
 * 通知器没启用、或没填 SMTP 与发信地址时，用户永远收不到信，所以在发信前提前拦下，
 * 并把要改的设置位置直接告诉操作人，而不是先回「已发送」再静默丢信。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDeliveryGuard {

    /** Halo 把通知器的发件配置存在这个 Secret 里，键为「通知器名.json」 */
    static final String NOTIFIER_SECRET = "notifier-setting-secret";
    static final String EMAIL_NOTIFIER_KEY = "default-email-notifier.json";

    static final String PLUGIN_SWITCH_OFF =
        "插件未开启邮件通知：请在「插件设置 → 消息通知」中打开「发送邮件通知」";
    static final String NOTIFIER_MISSING =
        "站点邮件通知器未配置：请在「系统 → 通知 → 邮件通知」中启用邮件通知器，并填写 SMTP 与发信地址";
    static final String NOTIFIER_DISABLED =
        "站点邮件通知器未启用：请在「系统 → 通知 → 邮件通知」中勾选「启用邮件通知器」";
    static final String NOTIFIER_INCOMPLETE =
        "站点邮件通知器缺少 SMTP 地址或发信地址：请在「系统 → 通知 → 邮件通知」中补全后重试";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveExtensionClient client;
    private final SettingConfigMember settingConfigMember;

    /**
     * @return 不可发信的原因；空表示可以发信
     */
    public Mono<Optional<String>> unavailabilityReason() {
        return settingConfigMember.getBasicConfig()
            .map(config -> config.isSendEmail()
                ? Optional.<String>empty()
                : Optional.of(PLUGIN_SWITCH_OFF))
            .defaultIfEmpty(Optional.of(PLUGIN_SWITCH_OFF))
            .flatMap(reason -> reason.isPresent() ? Mono.just(reason) : notifierReason())
            .onErrorResume(error -> {
                // 自检读不到配置时不应该拦住管理员，记下来就好
                log.warn("邮件配置自检失败，跳过检查", error);
                return Mono.just(Optional.empty());
            });
    }

    private Mono<Optional<String>> notifierReason() {
        return client.fetch(Secret.class, NOTIFIER_SECRET)
            .map(secret -> Optional.ofNullable(inspect(secret)))
            .defaultIfEmpty(Optional.of(NOTIFIER_MISSING));
    }

    private String inspect(Secret secret) {
        String raw = rawConfig(secret);
        if (StringUtils.isBlank(raw)) {
            return NOTIFIER_MISSING;
        }
        JsonNode sender;
        try {
            sender = MAPPER.readTree(raw).path("sender");
        } catch (Exception error) {
            log.warn("邮件通知器配置无法解析", error);
            return NOTIFIER_MISSING;
        }
        if (!sender.path("enable").asBoolean(false)) {
            return NOTIFIER_DISABLED;
        }
        String host = sender.path("host").asText("");
        // 发信地址留空时 Halo 用用户名发信，两者都为空才算没配
        String from = StringUtils.defaultIfBlank(sender.path("sender").asText(""),
            sender.path("username").asText(""));
        if (StringUtils.isAnyBlank(host, from)) {
            return NOTIFIER_INCOMPLETE;
        }
        return null;
    }

    private String rawConfig(Secret secret) {
        Map<String, String> stringData = secret.getStringData();
        if (stringData != null && stringData.get(EMAIL_NOTIFIER_KEY) != null) {
            return stringData.get(EMAIL_NOTIFIER_KEY);
        }
        Map<String, byte[]> data = secret.getData();
        byte[] encoded = data == null ? null : data.get(EMAIL_NOTIFIER_KEY);
        return encoded == null ? null : new String(encoded, StandardCharsets.UTF_8);
    }
}
