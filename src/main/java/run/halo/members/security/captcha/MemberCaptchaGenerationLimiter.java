package run.halo.members.security.captcha;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import run.halo.members.utils.RequestUtils;

/**
 * Caps how many challenge images a single client IP may ask for per minute.
 *
 * @author Sky
 * @since 1.5.0
 */
@Component
public class MemberCaptchaGenerationLimiter {

    private static final int MAX_IMAGES_PER_WINDOW = 10;
    static final int MAX_TRACKED_IPS = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int capacity;

    public MemberCaptchaGenerationLimiter() {
        this(Clock.systemUTC(), MAX_TRACKED_IPS);
    }

    MemberCaptchaGenerationLimiter(Clock clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    public synchronized Admission admit(ServerRequest request) {
        return admit(RequestUtils.getClientIP(request));
    }

    synchronized Admission admit(String remoteAddress) {
        Instant now = clock.instant();
        Window current = windows.get(remoteAddress);
        if (current != null && !now.isBefore(current.startedAt().plus(WINDOW))) {
            windows.remove(remoteAddress);
            current = null;
        }
        if (current == null) {
            makeRoom(now);
            windows.put(remoteAddress, new Window(now, 1));
            return Admission.allowedAdmission();
        }
        if (current.count() < MAX_IMAGES_PER_WINDOW) {
            windows.put(remoteAddress, new Window(current.startedAt(), current.count() + 1));
            return Admission.allowedAdmission();
        }
        Duration remaining = Duration.between(now, current.startedAt().plus(WINDOW));
        long retryAfter = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        return Admission.rejected(Math.max(1, retryAfter));
    }

    synchronized int trackedIpCount() {
        return windows.size();
    }

    synchronized boolean isTracked(String address) {
        return windows.containsKey(address);
    }

    private void makeRoom(Instant now) {
        if (windows.size() < capacity) {
            return;
        }
        windows.entrySet()
            .removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(WINDOW)));
        if (windows.size() < capacity) {
            return;
        }
        windows.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getValue().startedAt()))
            .ifPresent(entry -> windows.remove(entry.getKey()));
    }

    private record Window(Instant startedAt, int count) {
    }

    public record Admission(boolean allowed, long retryAfterSeconds) {

        private static Admission allowedAdmission() {
            return new Admission(true, 0);
        }

        private static Admission rejected(long retryAfterSeconds) {
            return new Admission(false, retryAfterSeconds);
        }
    }
}
