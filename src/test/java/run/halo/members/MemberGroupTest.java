package run.halo.members;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MemberGroup 实体测试
 */
class MemberGroupTest {

    @Test
    @DisplayName("测试 MemberGroup 基本属性")
    void testMemberGroupBasicProperties() {
        MemberGroup group = new MemberGroup();
        MemberGroup.MemberGroupSpec spec = new MemberGroup.MemberGroupSpec();
        group.setSpec(spec);
        
        // 测试 displayName
        spec.setDisplayName("默认分组");
        assertEquals("默认分组", spec.getDisplayName());
        
        // 测试 description
        spec.setDescription("这是默认分组");
        assertEquals("这是默认分组", spec.getDescription());
    }

}
