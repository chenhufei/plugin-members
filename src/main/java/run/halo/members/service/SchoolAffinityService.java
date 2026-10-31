package run.halo.members.service;

import java.util.List;
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
     * 分析学校之间的关联。
     *
     * @param schools 去重后的学校名称
     * @return 关联边列表；无关联时返回空列表
     */
    Mono<List<AffinityLink>> analyze(List<String> schools);

    /**
     * 一条关联边。
     *
     * @param a        学校 A
     * @param b        学校 B
     * @param strength 关联强度，0-1，越大越强
     * @param reason   判定理由（简短，可为空）
     */
    record AffinityLink(String a, String b, double strength, String reason) {
    }
}
