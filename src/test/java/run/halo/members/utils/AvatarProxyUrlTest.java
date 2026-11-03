package run.halo.members.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 头像代理白名单：只放行 QQ 头像 CDN 的固定主机与固定路径。
 * <p>
 * 这是匿名接口的第一道闸门，任何一条被放宽都等于把插件变成可访问内网的跳板。
 */
class AvatarProxyUrlTest {

    @Test
    @DisplayName("放行 QQ 头像的两个真实路径")
    void allowsQqAvatar() {
        assertEquals(
            Optional.of(URI.create("https://q1.qlogo.cn/g?b=qq&nk=12345678&s=640")),
            AvatarProxyUrl.resolve("https://q1.qlogo.cn/g?b=qq&nk=12345678&s=640"));
        assertTrue(AvatarProxyUrl.resolve(
            "https://q2.qlogo.cn/headimg_dl?dst_uin=12345678&spec=100").isPresent());
        assertTrue(AvatarProxyUrl.resolve(
            "https://thirdqq.qlogo.cn/g?b=sdk&nk=12345678&s=100").isPresent());
    }

    @Test
    @DisplayName("前后空白会被裁掉后再校验")
    void trimsWhitespace() {
        assertTrue(AvatarProxyUrl.resolve("  https://q1.qlogo.cn/g?b=qq&nk=1&s=100  ").isPresent());
    }

    @Test
    @DisplayName("拒绝非 https 与带端口的地址")
    void rejectsSchemeAndPort() {
        assertTrue(AvatarProxyUrl.resolve("http://q1.qlogo.cn/g?b=qq&nk=1&s=100").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("https://q1.qlogo.cn:8443/g?b=qq&nk=1&s=100").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("ftp://q1.qlogo.cn/g?b=qq&nk=1&s=100").isEmpty());
    }

    @Test
    @DisplayName("拒绝内网与伪装成白名单的主机")
    void rejectsInternalHosts() {
        for (String url : new String[] {
            "https://127.0.0.1/g?b=qq",
            "https://localhost/g?b=qq",
            "https://q1.qlogo.cn.evil.example/g?b=qq",
            "https://evil.example/g?b=qq&host=q1.qlogo.cn",
            "https://[::1]/g?b=qq",
        }) {
            assertTrue(AvatarProxyUrl.resolve(url).isEmpty(), url);
        }
    }

    @Test
    @DisplayName("拒绝用户信息、锚点与白名单以外的路径")
    void rejectsUserInfoFragmentAndPath() {
        assertTrue(AvatarProxyUrl.resolve("https://user:pass@q1.qlogo.cn/g?b=qq").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("https://q1.qlogo.cn/g?b=qq#frag").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("https://q1.qlogo.cn/admin?b=qq").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("https://q1.qlogo.cn/").isEmpty());
    }

    @Test
    @DisplayName("拒绝空地址、无查询串与超长地址")
    void rejectsEmptyAndOversized() {
        assertTrue(AvatarProxyUrl.resolve(null).isEmpty());
        assertTrue(AvatarProxyUrl.resolve("   ").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("https://q1.qlogo.cn/g").isEmpty());
        assertTrue(AvatarProxyUrl.resolve("https://q1.qlogo.cn/g?" + "a".repeat(600)).isEmpty());
        assertTrue(AvatarProxyUrl.resolve("not a url").isEmpty());
    }
}
