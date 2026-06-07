package run.halo.members.finders;

import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;

/**
 * 成员查询接口
 * @since 1.0.0
 */
public interface MemberFinder {

    /**
     * 获取所有已审核通过的成员（按分组）
     */
    Mono<ListResult<MemberGroupVo>> listApprovedMembers(@Nullable Integer page, @Nullable Integer size);

    /**
     * 获取所有已审核通过的成员（平铺列表）
     */
    Mono<ListResult<MemberVo>> listApprovedMemberList(@Nullable Integer page, @Nullable Integer size);

    /**
     * 获取指定分组的成员
     */
    Flux<MemberVo> listMembersByGroup(String groupName);

    /**
     * 获取所有分组
     */
    Flux<MemberGroupVo> listAllGroups();
}
