package run.halo.members.service;

import java.util.List;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * 学校关联分析：判断成员所属学校之间是否存在合作、地域/机构关联或共同的小组织。
 *
 * <p>该能力依赖 AI Foundation。站点未安装或未启用时不会注册实现，
 * 调用方需通过 {@code ObjectProvider} 取用，并在为空时降级为「无关联」。</p>
 *
 * @since 1.15.0
 */
public interface SchoolAffinityService {

    /**
     * 按给定选项分析学校之间的关联。
     *
     * @param schools 去重后的学校名称
     * @param options 分析维度与连线上限
     * @return 关联边列表；无关联时返回空列表
     */
    Mono<List<AffinityLink>> analyze(List<String> schools, AffinityOptions options);

    /**
     * 分析选项。维度取值为 {@code cooperation} / {@code region} / {@code organization}，
     * 未选中的维度不会进入提示词，从而让后台的「选项」真实影响模型判断范围。
     *
     * @param aspects  参与判断的维度
     * @param maxLinks 最多返回的关联边数量
     */
    record AffinityOptions(Set<String> aspects, int maxLinks) {

        public static final Set<String> ALL_ASPECTS =
            Set.of("cooperation", "region", "organization");

        public static AffinityOptions defaults() {
            return new AffinityOptions(ALL_ASPECTS, 12);
        }

        public AffinityOptions {
            if (aspects == null || aspects.isEmpty()) {
                aspects = ALL_ASPECTS;
            }
            if (maxLinks < 1) {
                maxLinks = 12;
            }
            maxLinks = Math.min(maxLinks, 40);
        }
    }

    /**
     * 一条关联边。
     *
     * @param a        学校 A
     * @param b        学校 B
     * @param strength 关联强度，0-1，越大越强
     * @param category 关联类型：cooperation / region / organization
     * @param reason   判定理由（简短，可为空）
     */
    record AffinityLink(String a, String b, double strength, String category, String reason) {
    }
}
