package run.halo.members;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @DisplayName("测试 MemberGroup 优先级")
    void testMemberGroupPriority() {
        MemberGroup.MemberGroupSpec spec = new MemberGroup.MemberGroupSpec();
        
        // 测试优先级
        spec.setPriority(0);
        assertEquals(0, spec.getPriority());
        
        // 测试高优先级
        spec.setPriority(100);
        assertEquals(100, spec.getPriority());
    }

    @Test
    @DisplayName("测试 MemberGroup 排序")
    void testMemberGroupSorting() {
        MemberGroup.MemberGroupSpec group1 = new MemberGroup.MemberGroupSpec();
        group1.setPriority(10);
        
        MemberGroup.MemberGroupSpec group2 = new MemberGroup.MemberGroupSpec();
        group2.setPriority(5);
        
        // group1 优先级应该大于 group2
        assertTrue(group1.getPriority() > group2.getPriority());
    }
}
