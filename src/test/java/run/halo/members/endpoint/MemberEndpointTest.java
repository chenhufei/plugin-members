package run.halo.members.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import run.halo.members.exception.ProblemResponses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            "default-group",
            "challenge-1",
            "AB234"
        );

        assertEquals("测试成员", request.displayName());
        assertEquals("test@example.com", request.email());
        assertEquals("清华大学", request.school());
        assertEquals("12345678", request.qq());
        assertEquals("https://qm.qq.com/xxxx", request.qqFriendLink());
        assertEquals("default-group", request.groupName());
        assertEquals("challenge-1", request.challengeId());
        assertEquals("AB234", request.captchaCode());
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
            null,
            null,
            null
        );

        assertNull(request.qqFriendLink());
        assertNull(request.groupName());
        assertNull(request.challengeId());
        assertNull(request.captchaCode());
    }

    @Test
    @DisplayName("错误响应统一为 RFC 7807：type/title/status/detail + 平铺扩展字段")
    void testProblemResponseShape() throws Exception {
        var problem = ProblemResponses.of(org.springframework.http.HttpStatus.CONFLICT,
                "duplicate-member-application", "成员申请重复", "该 QQ 号已登记")
            .withExtension("code", "CONFLICT");

        assertEquals("https://halo.run/probs/duplicate-member-application",
            problem.getType());
        assertEquals("成员申请重复", problem.getTitle());
        assertEquals(409, problem.getStatus());
        assertEquals("该 QQ 号已登记", problem.getDetail());

        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(problem);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"type\":\"https://halo.run/probs/duplicate-member-application\""), json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"status\":409"), json);
        // 扩展字段必须平铺在顶层，不能落进嵌套对象里
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"code\":\"CONFLICT\""), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"extensions\""), json);
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
