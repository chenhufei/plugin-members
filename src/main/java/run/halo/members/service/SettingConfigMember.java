package run.halo.members.service;

import lombok.Data;
import reactor.core.publisher.Mono;

/**
 * 成员管理插件配置服务接口
 * @since 1.0.34
 */
public interface SettingConfigMember {

    Mono<BasicConfig> getBasicConfig();

    Mono<EmailTemplateConfig> getEmailTemplateConfig();

    @Data
    class BasicConfig {
        public static final String GROUP = "basic";

        private boolean autoApprove;
        private boolean sendEmail;
        private String adminEmail;
        private String defaultGroupName;
        private String[] forbidSelectedGroupName;
        private boolean enableRateLimit = true;
        private Integer maxRequestsPerMinute = 10;
        private boolean enableSecurityBlocklist;
        private String blockedIpList;
        private String blockedUserAgentKeywords;
        private boolean rejectMissingUserAgent;

        public int normalizedMaxRequestsPerMinute() {
            if (maxRequestsPerMinute == null || maxRequestsPerMinute < 1) {
                return 10;
            }
            return Math.min(maxRequestsPerMinute, 1000);
        }
    }

    @Data
    class EmailTemplateConfig {
        public static final String GROUP = "emailTemplate";

        private String adminSubmitSubject;
        private String adminSubmitBody;
        private String userSubmitSubject;
        private String userSubmitBody;
        private String reviewResultSubject;
        private String reviewResultBody;
    }
}
