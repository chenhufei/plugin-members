package run.halo.members.service;

import lombok.Data;
import reactor.core.publisher.Mono;

/**
 * 成员管理插件配置服务接口
 * @since 1.0.34
 */
public interface SettingConfigMember {

    Mono<BasicConfig> getBasicConfig();

    @Data
    class BasicConfig {
        private boolean autoApprove;
        private boolean autoApproveWithdraw;
        private Integer pendingCapacity = 100;
        private boolean enableAdminNotification = true;
        private String adminUsername = "admin";
        private boolean sendEmail;
        private String adminEmail;
        private String defaultGroupName;
        private String[] forbidSelectedGroupName;
        private boolean enableRateLimit = true;
        private Integer maxRequestsPerMinute = 10;
        private boolean enableSubmitCaptcha = true;
        private boolean enableSecurityBlocklist;
        private String blockedIpList;
        private String blockedUserAgentKeywords;
        private boolean rejectMissingUserAgent;
        private boolean autoApproveClaim = false;
        private boolean enableClaimCaptcha = true;
        private boolean enableClaimEmail = true;

        public int normalizedMaxRequestsPerMinute() {
            if (maxRequestsPerMinute == null || maxRequestsPerMinute < 1) {
                return 10;
            }
            return Math.min(maxRequestsPerMinute, 1000);
        }

        public int normalizedPendingCapacity() {
            if (pendingCapacity == null || pendingCapacity < 1) {
                return 100;
            }
            return Math.min(pendingCapacity, 100_000);
        }
    }

    @Data
    class BasicGroupConfig {
        public static final String GROUP = "basic";

        private boolean autoApprove;
        private boolean autoApproveWithdraw;
        private Integer pendingCapacity = 100;
        private String defaultGroupName;
        private String[] forbidSelectedGroupName = new String[0];

        // 旧版所有配置都保存在 basic，保留这些字段用于无损迁移。
        private boolean sendEmail;
        private String adminEmail;
        private boolean enableRateLimit = true;
        private Integer maxRequestsPerMinute = 10;
        private boolean enableSubmitCaptcha = true;
        private boolean enableSecurityBlocklist;
        private String blockedIpList;
        private String blockedUserAgentKeywords;
        private boolean rejectMissingUserAgent;
        private boolean autoApproveClaim = false;
        private boolean enableClaimCaptcha = true;
        private boolean enableClaimEmail = true;
    }

    @Data
    class NotificationGroupConfig {
        public static final String GROUP = "notification";
        private boolean enableAdminNotification = true;
        private String adminUsername = "admin";
        private boolean sendEmail;
        private String adminEmail = "";
    }

    @Data
    class SecurityGroupConfig {
        public static final String GROUP = "security";
        private boolean enableRateLimit = true;
        private Integer maxRequestsPerMinute = 10;
        private boolean enableSubmitCaptcha = true;
        private boolean enableSecurityBlocklist;
        private String blockedIpList = "";
        private String blockedUserAgentKeywords = "";
        private boolean rejectMissingUserAgent;
    }
}
