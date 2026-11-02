package run.halo.members;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 打包资源测试
 */
class PluginApplicationTest {

    @Test
    @DisplayName("插件描述文件会被打包")
    void pluginDescriptorShouldExist() {
        assertNotNull(PluginApplicationTest.class.getResource("/plugin.yaml"));
        assertNotNull(PluginApplicationTest.class.getResource("/logo.svg"));
    }

    @Test
    @DisplayName("扩展配置会被打包")
    void extensionDescriptorsShouldExist() {
        assertNotNull(PluginApplicationTest.class.getResource("/extensions/settings.yaml"));
        assertNotNull(PluginApplicationTest.class.getResource("/extensions/notification.yaml"));
        assertNotNull(PluginApplicationTest.class.getResource("/extensions/notification-templates.yaml"));
    }
}
