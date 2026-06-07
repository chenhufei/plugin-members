package run.halo.members.security;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    @Test
    @DisplayName("同一窗口内超过次数会被拒绝")
    void shouldRejectRequestsAfterLimitExceeded() {
        RateLimitService rateLimitService = new RateLimitService();

        assertTrue(rateLimitService.isRequestAllowed("127.0.0.1", 1, Duration.ofMinutes(1)));
        assertFalse(rateLimitService.isRequestAllowed("127.0.0.1", 1, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("限流策略变化时会使用新策略")
    void shouldApplyChangedPolicyForExistingClient() {
        RateLimitService rateLimitService = new RateLimitService();

        assertTrue(rateLimitService.isRequestAllowed("127.0.0.2", 1, Duration.ofMinutes(1)));
        assertFalse(rateLimitService.isRequestAllowed("127.0.0.2", 1, Duration.ofMinutes(1)));

        assertTrue(rateLimitService.isRequestAllowed("127.0.0.2", 2, Duration.ofMinutes(1)));
        assertTrue(rateLimitService.isRequestAllowed("127.0.0.2", 2, Duration.ofMinutes(1)));
        assertFalse(rateLimitService.isRequestAllowed("127.0.0.2", 2, Duration.ofMinutes(1)));
    }
}
