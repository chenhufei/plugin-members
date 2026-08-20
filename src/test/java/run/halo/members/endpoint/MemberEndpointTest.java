package run.halo.members.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import run.halo.members.exception.RateLimitExceededException;

/**
 * Endpoint DTO 测试
 */
class MemberEndpointTest {

    @Test
    @DisplayName("成员提交请求会保留字段")
    void testSubmitRequestFields() {
        var request = new MemberEndpoint.MemberSubmitRequest(
            "测试成员",
            "test@example.com",
            "清华大学",
            "12345678",
            "https://qm.qq.com/xxxx",
            "default-group"
        );

        assertEquals("测试成员", request.displayName());
        assertEquals("test@example.com", request.email());
        assertEquals("清华大学", request.school());
        assertEquals("12345678", request.qq());
        assertEquals("https://qm.qq.com/xxxx", request.qqFriendLink());
        assertEquals("default-group", request.groupName());
    }

    @Test
    @DisplayName("成员提交请求允许可选字段为空")
    void testSubmitRequestOptionalFields() {
        var request = new MemberEndpoint.MemberSubmitRequest(
            "测试成员",
            "test@example.com",
            "清华大学",
            "12345678",
            null,
            null
        );

        assertNull(request.qqFriendLink());
        assertNull(request.groupName());
    }

    @Test
    @DisplayName("错误响应记录会暴露消息")
    void testErrorResponseRecords() {
        assertEquals("提交失败", new MemberEndpoint.ErrorResponse("提交失败").message());
        assertEquals("公开查询失败", new MemberPublicEndpoint.ErrorResponse("公开查询失败").message());
    }

    @Test
    @DisplayName("QQ 查询只对外部服务失败降级")
    void testQqErrorPropagationPolicy() {
        assertFalse(MemberEndpoint.shouldUseQqFallback(new SecurityException("blocked")));
        assertFalse(MemberEndpoint.shouldUseQqFallback(
            new RateLimitExceededException("limited")));
        assertTrue(MemberEndpoint.shouldUseQqFallback(new IllegalStateException("upstream")));
    }

    @Test
    @DisplayName("公开列表分页参数必须是有界数字")
    void testPublicQueryIntegerValidation() {
        assertEquals(1, MemberPublicEndpoint.parseQueryInt("1", "page", 1, 1_000_000));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MemberPublicEndpoint.parseQueryInt("abc", "page", 1, 1_000_000));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MemberPublicEndpoint.parseQueryInt("0", "page", 1, 1_000_000));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MemberPublicEndpoint.parseQueryInt("101", "size", 1, 100));
    }
}
