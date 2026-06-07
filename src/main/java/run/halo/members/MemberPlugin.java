package run.halo.members;

import org.springframework.stereotype.Component;

import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 成员管理插件主类
 * @since 1.0.0
 */
@Component
public class MemberPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public MemberPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        // 注册 Member 和 MemberGroup 扩展
        schemeManager.register(Member.class);
        schemeManager.register(MemberGroup.class);
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(Member.class));
        schemeManager.unregister(Scheme.buildFromType(MemberGroup.class));
    }
}
