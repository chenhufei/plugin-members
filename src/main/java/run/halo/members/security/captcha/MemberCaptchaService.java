package run.halo.members.security.captcha;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

/**
 * Issue and verification facade used by the anonymous member application endpoint.
 *
 * @author Sky
 * @since 1.5.0
 */
@Component
public class MemberCaptchaService {

    private final MemberCaptchaGenerationLimiter limiter;
    private final MemberCaptchaGenerator generator;
    private final MemberCaptchaStore store;
    private final MemberCaptchaRenderGate renderGate;

    public MemberCaptchaService(MemberCaptchaGenerationLimiter limiter,
        MemberCaptchaGenerator generator, MemberCaptchaStore store,
        MemberCaptchaRenderGate renderGate) {
        this.limiter = limiter;
        this.generator = generator;
        this.store = store;
        this.renderGate = renderGate;
    }

    public Mono<IssueResult> issue(ServerRequest request, String previousIdentifier) {
        var admission = limiter.admit(request);
        if (!admission.allowed()) {
            return Mono.just(IssueResult.rateLimited(admission.retryAfterSeconds()));
        }
        return renderGate.execute(() -> {
                var generated = generator.generate();
                String identifier = store.issue(generated.answer(), previousIdentifier);
                return IssueResult.issued(identifier, generated.png());
            })
            .onErrorResume(Exception.class,
                error -> Mono.just(IssueResult.unavailable()));
    }

    public boolean verifyChallenge(String identifier, String submittedAnswer) {
        return store.verifyAndConsume(identifier, submittedAnswer);
    }

    public enum IssueStatus {
        ISSUED,
        RATE_LIMITED,
        UNAVAILABLE
    }

    public record IssueResult(
        IssueStatus status,
        String identifier,
        byte[] png,
        long expiresInSeconds,
        long retryAfterSeconds
    ) {

        private static IssueResult issued(String identifier, byte[] png) {
            return new IssueResult(IssueStatus.ISSUED, identifier, png,
                MemberCaptchaStore.CHALLENGE_TTL.toSeconds(), 0);
        }

        private static IssueResult rateLimited(long retryAfterSeconds) {
            return new IssueResult(IssueStatus.RATE_LIMITED, null, null, 0,
                retryAfterSeconds);
        }

        private static IssueResult unavailable() {
            return new IssueResult(IssueStatus.UNAVAILABLE, null, null, 0, 0);
        }
    }
}
