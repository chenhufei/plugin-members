package run.halo.members;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CronMemberSubmitReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void cleansOnlyExpiredRejectedSubmissionsByDefault() {
        var spec = new CronMemberSubmit.Spec();

        assertTrue(CronMemberSubmitReconciler.shouldClean(
            "REJECTED", NOW.minusSeconds(31L * 24 * 60 * 60), spec, NOW));
        assertFalse(CronMemberSubmitReconciler.shouldClean(
            "REJECTED", NOW.minusSeconds(29L * 24 * 60 * 60), spec, NOW));
        assertFalse(CronMemberSubmitReconciler.shouldClean(
            "PENDING", NOW.minusSeconds(120L * 24 * 60 * 60), spec, NOW));
        assertFalse(CronMemberSubmitReconciler.shouldClean(
            "APPROVED", NOW.minusSeconds(365L * 24 * 60 * 60), spec, NOW));
    }

    @Test
    void pendingCleanupRequiresExplicitOptIn() {
        var spec = new CronMemberSubmit.Spec();
        spec.setCleanupPending(true);
        spec.setPendingRetentionDays(90);

        assertTrue(CronMemberSubmitReconciler.shouldClean(
            "PENDING", NOW.minusSeconds(91L * 24 * 60 * 60), spec, NOW));
        assertFalse(CronMemberSubmitReconciler.shouldClean(
            "PENDING", NOW.minusSeconds(89L * 24 * 60 * 60), spec, NOW));
    }
}
