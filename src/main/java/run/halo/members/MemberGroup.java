package run.halo.members;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 成员分组实体
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "member.plugin.halo.run", version = "v1alpha1", 
     kind = "MemberGroup", plural = "membergroups", singular = "membergroup")
public class MemberGroup extends AbstractExtension {

    private MemberGroupSpec spec;

    @Data
    public static class MemberGroupSpec {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分组显示名称")
        private String displayName;

        @Schema(description = "优先级")
        private Integer priority;

        @Schema(description = "分组描述")
        private String description;
    }
}
