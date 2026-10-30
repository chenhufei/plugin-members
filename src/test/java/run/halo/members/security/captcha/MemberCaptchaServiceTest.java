package run.halo.members.security.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Font;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.scheduler.Schedulers;
import run.halo.members.support.MutableClock;

class MemberCaptchaServiceTest {

    @Test
    void shouldIssueChallengeThatVerifiesOnce() {
        var service = service(4, 10);

        var issued = service.issue(null, null).block();

        assertThat(issued).isNotNull();
        assertThat(issued.status()).isEqualTo(MemberCaptchaService.IssueStatus.ISSUED);
        assertThat(issued.identifier()).isNotBlank();
        assertThat(issued.png()).isNotEmpty();
        assertThat(issued.expiresInSeconds())
            .isEqualTo(MemberCaptchaStore.CHALLENGE_TTL.toSeconds());
        assertThat(service.verifyChallenge(issued.identifier(), "AAAAA")).isTrue();
        assertThat(service.verifyChallenge(issued.identifier(), "AAAAA")).isFalse();
    }

    @Test
    void shouldReportRateLimitedAfterTenImagesFromTheSameIp() {
        var service = service(4, 100);

        for (int index = 0; index < 10; index++) {
            assertThat(service.issue(null, null).block().status())
                .isEqualTo(MemberCaptchaService.IssueStatus.ISSUED);
        }

        assertThat(service.issue(null, null).block())
            .extracting(MemberCaptchaService.IssueResult::status,
                MemberCaptchaService.IssueResult::retryAfterSeconds)
            .containsExactly(MemberCaptchaService.IssueStatus.RATE_LIMITED, 60L);
    }

    @Test
    void shouldReportUnavailableWhenRenderingIsBusy() {
        var service = service(0, 10);

        assertThat(service.issue(null, null).block().status())
            .isEqualTo(MemberCaptchaService.IssueStatus.UNAVAILABLE);
    }

    @Test
    void shouldReportUnavailableWhenChallengeStoreIsFull() {
        var service = service(4, 1);

        assertThat(service.issue(null, null).block().status())
            .isEqualTo(MemberCaptchaService.IssueStatus.ISSUED);
        assertThat(service.issue(null, null).block().status())
            .isEqualTo(MemberCaptchaService.IssueStatus.UNAVAILABLE);
    }

    @Test
    void shouldRejectUnknownChallenge() {
        var service = service(4, 10);

        assertThat(service.verifyChallenge("challenge-1", "ABCDE")).isFalse();
        assertThat(service.verifyChallenge(null, "ABCDE")).isFalse();
    }

    private static MemberCaptchaService service(int renderSlots, int challengeCapacity) {
        var clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        var sequence = new AtomicInteger();
        var store = new MemberCaptchaStore(clock, challengeCapacity,
            () -> "challenge-" + sequence.incrementAndGet());
        var generator = new MemberCaptchaGenerator(new ZeroRandom(),
            new Font(Font.MONOSPACED, Font.BOLD, 30));
        var limiter = new FixedIpLimiter(clock, 10_000);
        return new MemberCaptchaService(limiter, generator, store,
            new MemberCaptchaRenderGate(renderSlots, Schedulers.immediate()));
    }

    private static final class FixedIpLimiter extends MemberCaptchaGenerationLimiter {

        private FixedIpLimiter(java.time.Clock clock, int capacity) {
            super(clock, capacity);
        }

        @Override
        public Admission admit(ServerRequest request) {
            return admit("192.0.2.10");
        }
    }

    private static final class ZeroRandom implements RandomGenerator {

        @Override
        public long nextLong() {
            return 0;
        }
    }
}
