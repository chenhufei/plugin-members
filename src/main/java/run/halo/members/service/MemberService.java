package run.halo.members.service;

import reactor.core.publisher.Mono;
import run.halo.members.Member;
import run.halo.members.endpoint.MemberEndpoint;

/**
 * 成员服务接口
 * @since 1.0.0
 */
public interface MemberService {

    /**
     * 提交成员申请
     */
    Mono<Member> submitMember(MemberEndpoint.MemberSubmitRequest request);
}
