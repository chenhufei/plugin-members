package run.halo.members.vo;

import lombok.Builder;
import lombok.Data;
import run.halo.app.extension.MetadataOperator;
import run.halo.members.Member;

/**
 * 成员视图对象
 * @since 1.0.0
 */
@Data
@Builder
public class MemberVo {
    
    private MetadataOperator metadata;
    
    private Member.MemberSpec spec;

    public static MemberVo from(Member member) {
        return MemberVo.builder()
            .metadata(member.getMetadata())
            .spec(member.getSpec())
            .build();
    }
}
