package run.halo.members;

/**
 * 成员管理插件常量
 * @since 1.0.34
 */
public enum MemberConstant {
    ;

    public static final String MARK_AS_NOTIFIED = "member.plugin.halo.run/mark-as-notified";
    
    public static final String ADMIN_MEMBER_SUBMIT = "admin-member-submit";
    
    public static final String USER_MEMBER_SUBMIT = "user-member-submit";
    
    public static final String REVIEW_MEMBER_SUBMIT = "review-member-submit";
    
    public static final String REVIEW_MEMBER_REJECT = "review-member-reject";
    
    public static final String FINALIZER_NAME = "member.plugin.halo.run/finalizer";
    
    public static final String REVIEW_DESCRIPTION = "member.plugin.halo.run/review-description";

    // === 撤回申请相关常量 ===

    /**
     * 通知中心订阅：用户提交撤回申请后，通知管理员
     */
    public static final String ADMIN_MEMBER_WITHDRAW = "admin-member-withdraw";

    /**
     * 通知中心订阅：管理员处理撤回申请后，通知用户
     */
    public static final String USER_MEMBER_WITHDRAW_REVIEW = "user-member-withdraw-review";

    public static final String USER_MEMBER_WITHDRAW_CODE = "user-member-withdraw-code";

    /**
     * 最终保护器：用于标记成员撤回申请已处理
     */
    public static final String WITHDRAW_FINALIZER_NAME = "member.plugin.halo.run/withdraw-finalizer";
}
