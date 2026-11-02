package run.halo.members;

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
        private String displayName;

        private String description;
    }
}
