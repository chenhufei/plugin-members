package run.halo.members;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 成员实体
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "member.plugin.halo.run", version = "v1alpha1",
        kind = "Member", plural = "members", singular = "member")
public class Member extends AbstractExtension {

    private MemberSpec spec;

    @Data
    public static class MemberSpec {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "账号名称")
        private String displayName;

        @Schema(description = "联系邮箱")
        private String email;

        @Schema(description = "账号所属学校")
        private String school;

        @Schema(description = "QQ号")
        private String qq;



        @Schema(description = "头像地址")
        private String avatar;

        @Schema(description = "QQ加好友链接")
        private String qqFriendLink;

        @Schema(description = "优先级")
        private Integer priority;

        @Schema(description = "所属分组")
        private String groupName;

        @Schema(description = "审核状态: PENDING(待审核), APPROVED(已通过), REJECTED(已拒绝)")
        private String status = "PENDING";

        // 保留旧字段以兼容现有数据
        @Schema(description = "个人网站（已废弃）")
        @Deprecated
        private String website;

        @Schema(description = "个人简介（已废弃）")
        @Deprecated
        private String description;
    }
}
