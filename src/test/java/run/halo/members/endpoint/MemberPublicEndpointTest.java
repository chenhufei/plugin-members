package run.halo.members.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.members.Member;
import run.halo.members.vo.MemberVo;

/**
 * 匿名只读 API 的字段脱敏测试
 */
class MemberPublicEndpointTest {

    private static MemberVo fullMember() {
        Metadata metadata = new Metadata();
        metadata.setName("member-abc");
        metadata.setCreationTimestamp(Instant.parse("2026-10-28T00:00:00Z"));
        metadata.setAnnotations(Map.of(
            "member.plugin.halo.run/submission-notification", "sent"));
        metadata.setFinalizers(Set.of("member.plugin.halo.run/finalizer"));
        metadata.setVersion(7L);

        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setDisplayName("张三");
        spec.setEmail("zhangsan@example.com");
        spec.setQq("12345678");
        spec.setSchool("清华大学");
        spec.setAvatar("https://q1.qlogo.cn/g?b=qq&nk=12345678&s=640");
        spec.setQqFriendLink("https://wpa.qq.com/msgrd?v=3&uin=12345678");
        spec.setGroupName("union");
        spec.setStatus("APPROVED");
        spec.setOrigin("FORM");
        spec.setPriority(9);
        spec.setBackground("https://internal.example.com/bg.png");
        return MemberVo.builder().metadata(metadata).spec(spec).build();
    }

    @Test
    @DisplayName("公开成员列表只保留展示字段")
    void stripsPrivateFields() {
        var result = new ListResult<>(1, 20, 1, List.of(fullMember()));

        var publicResult = MemberPublicEndpoint.toPublicView(result);
        var publicMember = publicResult.getItems().get(0);

        assertEquals("张三", publicMember.getSpec().getDisplayName());
        assertEquals("清华大学", publicMember.getSpec().getSchool());
        assertEquals("union", publicMember.getSpec().getGroupName());
        assertEquals("https://wpa.qq.com/msgrd?v=3&uin=12345678",
            publicMember.getSpec().getQqFriendLink());

        assertNull(publicMember.getSpec().getEmail());
        assertNull(publicMember.getSpec().getQq());
        assertNull(publicMember.getSpec().getStatus());
        assertNull(publicMember.getSpec().getOrigin());
        assertNull(publicMember.getSpec().getPriority());
        assertNull(publicMember.getSpec().getBackground());
    }

    @Test
    @DisplayName("公开成员列表不泄露内部标注与版本")
    void stripsInternalMetadata() {
        var publicMember = MemberPublicEndpoint.toPublicView(fullMember());

        assertEquals("member-abc", publicMember.getMetadata().getName());
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"),
            publicMember.getMetadata().getCreationTimestamp());
        assertNull(publicMember.getMetadata().getAnnotations());
        assertNull(publicMember.getMetadata().getFinalizers());
        assertNull(publicMember.getMetadata().getVersion());
    }

    @Test
    @DisplayName("脱敏不修改缓存中的原始成员对象")
    void doesNotMutateSource() {
        var source = fullMember();
        MemberPublicEndpoint.toPublicView(source);

        assertEquals("zhangsan@example.com", source.getSpec().getEmail());
        assertEquals("APPROVED", source.getSpec().getStatus());
        assertEquals(1, source.getMetadata().getFinalizers().size());
    }

    @Test
    @DisplayName("分页信息保持不变")
    void keepsPagination() {
        var result = new ListResult<>(2, 20, 45, List.of(fullMember()));

        var publicResult = MemberPublicEndpoint.toPublicView(result);

        assertEquals(2, publicResult.getPage());
        assertEquals(20, publicResult.getSize());
        assertEquals(45, publicResult.getTotal());
    }
}
