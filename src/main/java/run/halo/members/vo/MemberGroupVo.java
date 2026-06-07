package run.halo.members.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import run.halo.app.extension.MetadataOperator;
import run.halo.members.MemberGroup;

/**
 * 成员分组视图对象
 * @since 1.0.0
 */
@Data
@Builder
public class MemberGroupVo {
    
    private MetadataOperator metadata;
    
    private MemberGroup.MemberGroupSpec spec;
    
    private List<MemberVo> members;

    public static MemberGroupVo from(MemberGroup group) {
        return MemberGroupVo.builder()
            .metadata(group.getMetadata())
            .spec(group.getSpec())
            .build();
    }
}
