package run.halo.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_SENT;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_SENT;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.Metadata;
import run.halo.app.notification.NotificationCenter;
import run.halo.members.finders.impl.MemberFinderImpl;

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

    @Test
    void adminStationAndEmailSubscriptionsUseDifferentIdentities() {
        var notificationCenter = mock(NotificationCenter.class);
        when(notificationCenter.subscribe(any(), any())).thenReturn(Mono.just(new Subscription()));
        var reconciler = new MemberReconciler(mock(run.halo.app.extension.ExtensionClient.class),
            mock(org.springframework.context.ApplicationEventPublisher.class), notificationCenter,
            mock(run.halo.members.service.SettingConfigMember.class), mock(MemberFinderImpl.class));

        reconciler.adminNoticeSubscription("admin");
        reconciler.adminEmailSubscription("admin@example.com");

        var subscriberCaptor = ArgumentCaptor.forClass(Subscription.Subscriber.class);
        var reasonCaptor = ArgumentCaptor.forClass(Subscription.InterestReason.class);
        verify(notificationCenter, org.mockito.Mockito.times(2))
            .subscribe(subscriberCaptor.capture(), reasonCaptor.capture());
        assertEquals("admin", subscriberCaptor.getAllValues().get(0).getName());
        assertEquals("anonymousUser#admin@example.com",
            subscriberCaptor.getAllValues().get(1).getName());
        assertEquals("props.adminUsername == 'admin'",
            reasonCaptor.getAllValues().get(0).getExpression());
        assertEquals("props.adminEmail == 'admin@example.com'",
            reasonCaptor.getAllValues().get(1).getExpression());
    }

    private Member memberWithAnnotations(HashMap<String, String> annotations) {
        var member = new Member();
        var metadata = new Metadata();
        metadata.setAnnotations(annotations);
        member.setMetadata(metadata);
        return member;
    }
}
