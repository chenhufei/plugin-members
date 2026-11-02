package run.halo.members.service;

import reactor.core.publisher.Mono;

/**
 * 撤回申请审核服务：控制台端点与 MCP 工具共用同一套状态迁移与通知逻辑。
 *
 * @since 1.6.1
 */
public interface MemberWithdrawReviewService {

    Mono<ReviewResult> review(String memberName, boolean approved);

    record ReviewResult(boolean approved, String statusBefore, String message) {
    }

    class MemberNotFoundException extends RuntimeException {
        public MemberNotFoundException(String memberName) {
            super("Member " + memberName + " was not found.");
        }
    }

    class AlreadyReviewedException extends RuntimeException {
        public AlreadyReviewedException(String memberName) {
            super("Withdrawal request of member " + memberName + " was already reviewed.");
        }
    }
}
