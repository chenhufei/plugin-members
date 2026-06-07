package run.halo.members.finders;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import run.halo.app.theme.finders.Finder;

/**
 * 成员查询服务 - 供主题模板使用
 * @since 1.0.0
 */
@Finder("memberFinder")
@Component
@RequiredArgsConstructor
public class MemberPublicQueryService {

    private final MemberFinder memberFinder;

    /**
     * 获取已审核通过的成员列表（按分组）
     * 在模板中使用: ${memberFinder.listApprovedMembers()}
     */
    public Object listApprovedMembers() {
        return memberFinder.listApprovedMembers(null, null).block();
    }

    /**
     * 获取所有分组
     * 在模板中使用: ${memberFinder.listAllGroups()}
     */
    public Object listAllGroups() {
        return memberFinder.listAllGroups().collectList().block();
    }
}
