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

    /**
     * 同一成员的另一次审核尚未结束，等待超时后抛出：调用方应提示稍后重试。
     */
    class ReviewInProgressException extends RuntimeException {
        public ReviewInProgressException(String memberName) {
            super("Another review of member " + memberName + " is still in progress.");
        }
    }
}
