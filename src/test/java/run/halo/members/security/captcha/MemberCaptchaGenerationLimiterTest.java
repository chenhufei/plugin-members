package run.halo.members.security.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import run.halo.members.support.MutableClock;

class MemberCaptchaGenerationLimiterTest {

    @Test
    void shouldAllowTenImagesPerIpAndReturnRetryAfter() {
        assertThat(MemberCaptchaGenerationLimiter.MAX_TRACKED_IPS).isEqualTo(10_000);
        var clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        var limiter = new MemberCaptchaGenerationLimiter(clock, 10_000);

        for (int index = 0; index < 10; index++) {
            assertThat(limiter.admit("192.0.2.10").allowed()).isTrue();
        }
        assertThat(limiter.admit("192.0.2.10"))
            .extracting(MemberCaptchaGenerationLimiter.Admission::allowed,
                MemberCaptchaGenerationLimiter.Admission::retryAfterSeconds)
            .containsExactly(false, 60L);

        clock.advance(Duration.ofSeconds(31));
        assertThat(limiter.admit("192.0.2.10").retryAfterSeconds()).isEqualTo(29);
        clock.advance(Duration.ofSeconds(29));
        assertThat(limiter.admit("192.0.2.10").allowed()).isTrue();
    }

    @Test
    void shouldRoundRetryAfterUpToTheRemainingWholeSecond() {
        var clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        var limiter = new MemberCaptchaGenerationLimiter(clock, 10_000);

        for (int index = 0; index < 10; index++) {
            limiter.admit("192.0.2.10");
        }
        clock.advance(Duration.ofSeconds(30).plusMillis(200));

        assertThat(limiter.admit("192.0.2.10").retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void shouldTrackEveryIpSeparately() {
        var clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        var limiter = new MemberCaptchaGenerationLimiter(clock, 10_000);

        for (int index = 0; index < 10; index++) {
            limiter.admit("192.0.2.10");
        }

        assertThat(limiter.admit("192.0.2.11").allowed()).isTrue();
        assertThat(limiter.trackedIpCount()).isEqualTo(2);
    }

    @Test
    void shouldEvictExpiredWindowsBeforeTheTrackedIpCap() {
        var clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        var limiter = new MemberCaptchaGenerationLimiter(clock, 3);

        limiter.admit("192.0.2.1");
        limiter.admit("192.0.2.2");
        limiter.admit("192.0.2.3");
        clock.advance(Duration.ofSeconds(61));
        limiter.admit("192.0.2.4");

        assertThat(limiter.trackedIpCount()).isEqualTo(1);
        assertThat(limiter.isTracked("192.0.2.4")).isTrue();
    }

    @Test
    void shouldEvictOldestWindowWhenCapIsReachedWithoutExpiry() {
        var clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        var limiter = new MemberCaptchaGenerationLimiter(clock, 2);

        limiter.admit("192.0.2.1");
        clock.advance(Duration.ofSeconds(1));
        limiter.admit("192.0.2.2");
        clock.advance(Duration.ofSeconds(1));
        limiter.admit("192.0.2.3");

        assertThat(limiter.trackedIpCount()).isEqualTo(2);
        assertThat(limiter.isTracked("192.0.2.1")).isFalse();
        assertThat(limiter.isTracked("192.0.2.3")).isTrue();
    }
}
