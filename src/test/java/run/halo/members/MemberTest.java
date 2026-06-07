package run.halo.members;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Member 实体测试
 */
class MemberTest {

    @Test
    @DisplayName("测试 Member 基本属性")
    void testMemberBasicProperties() {
        Member member = new Member();
        Member.MemberSpec spec = new Member.MemberSpec();
        member.setSpec(spec);
        
        // 测试 displayName
        spec.setDisplayName("测试成员");
        assertEquals("测试成员", spec.getDisplayName());
        
        // 测试 email
        spec.setEmail("test@example.com");
        assertEquals("test@example.com", spec.getEmail());
        
        // 测试 qq
        spec.setQq("123456");
        assertEquals("123456", spec.getQq());
        
        // 测试 school
        spec.setSchool("清华大学");
        assertEquals("清华大学", spec.getSchool());
    }

    @Test
    @DisplayName("测试 Member 默认和可变状态")
    void testMemberStatus() {
        Member.MemberSpec spec = new Member.MemberSpec();

        assertEquals("PENDING", spec.getStatus());

        spec.setStatus("APPROVED");
        assertEquals("APPROVED", spec.getStatus());

        spec.setStatus("REJECTED");
        assertEquals("REJECTED", spec.getStatus());
    }

    @Test
    @DisplayName("测试兼容旧数据的废弃字段仍可用")
    void testDeprecatedFieldsRemainAvailable() {
        Member.MemberSpec spec = new Member.MemberSpec();

        spec.setWebsite("https://example.com");
        spec.setDescription("兼容旧数据");

        assertEquals("https://example.com", spec.getWebsite());
        assertEquals("兼容旧数据", spec.getDescription());
    }
}
