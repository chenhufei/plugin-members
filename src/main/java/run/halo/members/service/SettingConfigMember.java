package run.halo.members.service;

import java.util.ArrayList;
import java.util.List;
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
        private String claimVerifyMode;
        // 旧字段仅用于配置迁移，新配置请使用 claimVerifyMode
        private boolean enableClaimCaptcha = true;
        private boolean enableClaimEmail = true;

        /** 学校关联分析：总开关。关闭时不调用任何大模型。 */
        private boolean affinityEnabled = false;
        /** 参与分析的关联维度，取值见 AFFINITY_ASPECT_KEYS。 */
        private String[] affinityAspects;
        /** 返回给前端的最大连线数。 */
        private Integer affinityMaxLinks = 12;

        /** 支持的关联维度：合作、地域/机构关联、共同小组织。 */
        public static final List<String> AFFINITY_ASPECT_KEYS =
            List.of("cooperation", "region", "organization");

        /**
         * 参与分析的关联维度。未配置或配置全部非法时回退为三类全开，
         * 避免因后台选项被清空导致分析结果为空。
         */
        public List<String> effectiveAffinityAspects() {
            if (affinityAspects == null || affinityAspects.length == 0) {
                return AFFINITY_ASPECT_KEYS;
            }
            var valid = new ArrayList<String>();
            for (String aspect : affinityAspects) {
                if (aspect != null && AFFINITY_ASPECT_KEYS.contains(aspect)
                    && !valid.contains(aspect)) {
                    valid.add(aspect);
                }
            }
            return valid.isEmpty() ? AFFINITY_ASPECT_KEYS : List.copyOf(valid);
        }

        /** 返回给前端的最大连线数，收敛到 1-40。 */
        public int effectiveAffinityMaxLinks() {
            if (affinityMaxLinks == null || affinityMaxLinks < 1) {
                return 12;
            }
            return Math.min(affinityMaxLinks, 40);
        }

        /**
         * 认领验证方式：captcha / email / none。
         * 优先使用新配置；旧配置按此前认领前端仅实现邮箱验证码的实际情况迁移，
         * 避免升级后既有站点突然要求无法完成的图形验证码。
         */
        public String effectiveClaimVerifyMode() {
            if (claimVerifyMode != null && !claimVerifyMode.isBlank()) {
                return claimVerifyMode;
            }
            if (!enableClaimCaptcha && !enableClaimEmail) {
                return "none";
            }
            return "email";
        }

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
        private String claimVerifyMode;
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

    /**
     * 学校关联分析（关系网连线）的独立设置组。
     *
     * <p>单独成组，便于管理员明确「这是一项会调用大模型的能力」而独立开关，
     * 不与安全/通知类配置混在一起。</p>
     */
    /**
     * 成员认领（自助补全 QQ 加好友链接）的独立设置组。
     *
     * <p>此前认领选项写在 security 组，但代码从 basic 组读取，导致后台选择的
     * 验证方式实际从未生效。独立成组后读写同源。
     */
    @Data
    class ClaimGroupConfig {
        public static final String GROUP = "claim";
        private boolean autoApproveClaim = false;
        private String claimVerifyMode;
    }

    @Data
    class AffinityGroupConfig {
        public static final String GROUP = "affinity";
        private boolean affinityEnabled = false;
        private String[] affinityAspects;
        private Integer affinityMaxLinks = 12;
    }
}
