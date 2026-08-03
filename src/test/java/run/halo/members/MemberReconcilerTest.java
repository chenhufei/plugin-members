package run.halo.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_SENT;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_SENT;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

class MemberReconcilerTest {

    @Test
    void onlyExplicitlyPendingSubmissionCanTriggerNotification() {
        var existingMember = memberWithAnnotations(new HashMap<>());
        assertFalse(MemberReconciler.tryMarkSubmissionAsNotified(existingMember));

        var submittedMember = memberWithAnnotations(new HashMap<>());
        submittedMember.getMetadata().getAnnotations()
            .put(SUBMISSION_NOTIFICATION, SUBMISSION_NOTIFICATION_PENDING);

        assertTrue(MemberReconciler.tryMarkSubmissionAsNotified(submittedMember));
        assertEquals(SUBMISSION_NOTIFICATION_SENT,
            submittedMember.getMetadata().getAnnotations().get(SUBMISSION_NOTIFICATION));
        assertFalse(MemberReconciler.tryMarkSubmissionAsNotified(submittedMember));
    }

    @Test
    void onlyExplicitReviewTransitionCanTriggerNotification() {
        var existingMember = memberWithAnnotations(new HashMap<>());
        assertFalse(MemberReconciler.tryConsumeReviewNotification(existingMember));

        var reviewedMember = memberWithAnnotations(new HashMap<>());
        reviewedMember.getMetadata().getAnnotations()
            .put(REVIEW_NOTIFICATION, REVIEW_NOTIFICATION_PENDING);

        assertTrue(MemberReconciler.tryConsumeReviewNotification(reviewedMember));
        assertEquals(REVIEW_NOTIFICATION_SENT,
            reviewedMember.getMetadata().getAnnotations().get(REVIEW_NOTIFICATION));
        assertFalse(MemberReconciler.tryConsumeReviewNotification(reviewedMember));
    }

    private Member memberWithAnnotations(HashMap<String, String> annotations) {
        var member = new Member();
        var metadata = new Metadata();
        metadata.setAnnotations(annotations);
        member.setMetadata(metadata);
        return member;
    }
}
