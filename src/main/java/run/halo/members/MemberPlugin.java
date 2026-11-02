package run.halo.members;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
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
        // 注册 Member、MemberGroup 和自助提交定时任务扩展
        //
        // 除状态外，QQ / 邮箱 / 分组 / 学校也要建索引：匿名提交的重复检测原来靠
        // listAll 全表读进内存再 filter，成员越多越慢，且每个请求都重复一遍。
        // 索引值统一做 trim（邮箱再转小写），这样查询侧不必依赖写入时的原始大小写。
        schemeManager.register(Member.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<Member, String>single("spec.status", String.class)
                .indexFunc(member -> member.getSpec() == null
                    ? null : member.getSpec().getStatus()));
            indexSpecs.add(IndexSpecs.<Member, String>single("spec.qq", String.class)
                .indexFunc(member -> member.getSpec() == null
                    ? null : StringUtils.trimToNull(member.getSpec().getQq())));
            indexSpecs.add(IndexSpecs.<Member, String>single("spec.email", String.class)
                .indexFunc(member -> member.getSpec() == null
                    ? null : StringUtils.trimToNull(
                        StringUtils.lowerCase(member.getSpec().getEmail()))));
            indexSpecs.add(IndexSpecs.<Member, String>single("spec.groupName", String.class)
                .indexFunc(member -> member.getSpec() == null
                    ? null : StringUtils.trimToNull(member.getSpec().getGroupName())));
            indexSpecs.add(IndexSpecs.<Member, String>single("spec.school", String.class)
                .indexFunc(member -> member.getSpec() == null
                    ? null : StringUtils.trimToNull(member.getSpec().getSchool())));
        });
        schemeManager.register(MemberGroup.class);
        schemeManager.register(CronMemberSubmit.class);
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(Member.class));
        schemeManager.unregister(Scheme.buildFromType(MemberGroup.class));
        schemeManager.unregister(Scheme.buildFromType(CronMemberSubmit.class));
    }
}
