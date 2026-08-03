package run.halo.members;

import static run.halo.app.extension.ExtensionUtil.defaultSort;
import static run.halo.app.extension.ExtensionUtil.notDeleting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.router.selector.FieldSelector;

@Slf4j
@Component
public class CronMemberSubmitReconciler implements Reconciler<Reconciler.Request> {

    static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    private final ExtensionClient client;
    private Clock clock = Clock.systemUTC();

    public CronMemberSubmitReconciler(ExtensionClient client) {
        this.client = client;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Result reconcile(Request request) {
        return client.fetch(CronMemberSubmit.class, request.name())
            .map(this::reconcileTask)
            .orElseGet(Result::doNotRetry);
    }

    private Result reconcileTask(CronMemberSubmit task) {
        if (ExtensionUtil.isDeleted(task)) {
            return Result.doNotRetry();
        }

        var spec = task.getSpec();
        var status = task.getStatus();
        if (spec == null) {
            return Result.doNotRetry();
        }
        if (status == null) {
            status = new CronMemberSubmit.Status();
            task.setStatus(status);
        }

        if (!spec.isEnabled()) {
            if (status.getNextSchedulingTimestamp() != null) {
                status.setNextSchedulingTimestamp(null);
                status.setLastMessage("定时任务已停用");
                client.update(task);
            }
            return Result.doNotRetry();
        }

        String cron = spec.getCron();
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            if (!Objects.equals(status.getLastMessage(), "定时表达式无效")) {
                status.setNextSchedulingTimestamp(null);
                status.setLastMessage("定时表达式无效");
                client.update(task);
            }
            return Result.doNotRetry();
        }

        Instant now = Instant.now(clock);
        Instant reference = status.getLastScheduledTimestamp();
        if (reference == null) {
            reference = task.getMetadata().getCreationTimestamp();
        }
        if (reference == null) {
            reference = now;
        }

        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime dueAt = expression.next(reference.atZone(ZONE_ID));
        if (dueAt == null) {
            return Result.doNotRetry();
        }

        if (dueAt.toInstant().isAfter(now)) {
            Instant next = dueAt.toInstant();
            if (!Objects.equals(status.getNextSchedulingTimestamp(), next)) {
                status.setNextSchedulingTimestamp(next);
                status.setLastMessage("等待下次执行");
                client.update(task);
            }
            return requeueAt(now, next);
        }

        int cleanedCount = cleanupExpiredSubmissions(spec, now);
        Instant next = nextExecution(expression, now);
        status.setLastScheduledTimestamp(now);
        status.setNextSchedulingTimestamp(next);
        status.setLastCleanedCount(cleanedCount);
        status.setLastMessage("本次清理 " + cleanedCount + " 条过期申请");
        client.update(task);
        log.info("Cleaned {} expired member submissions, next execution at {}", cleanedCount, next);
        return next == null ? Result.doNotRetry() : requeueAt(now, next);
    }

    private int cleanupExpiredSubmissions(CronMemberSubmit.Spec spec, Instant now) {
        var options = new ListOptions();
        options.setFieldSelector(FieldSelector.of(notDeleting()));
        int cleaned = 0;
        for (Member member : client.listAll(Member.class, options, defaultSort())) {
            Instant createdAt = member.getMetadata().getCreationTimestamp();
            String memberStatus = member.getSpec() == null ? null : member.getSpec().getStatus();
            if (shouldClean(memberStatus, createdAt, spec, now)) {
                client.delete(member);
                cleaned++;
            }
        }
        return cleaned;
    }

    static boolean shouldClean(String status, Instant createdAt, CronMemberSubmit.Spec spec,
        Instant now) {
        if (status == null || createdAt == null || spec == null || now == null) {
            return false;
        }
        if ("REJECTED".equals(status) && spec.isCleanupRejected()) {
            return isOlderThan(createdAt, now, spec.getRejectedRetentionDays());
        }
        if ("PENDING".equals(status) && spec.isCleanupPending()) {
            return isOlderThan(createdAt, now, spec.getPendingRetentionDays());
        }
        return false;
    }

    private static boolean isOlderThan(Instant createdAt, Instant now, int retentionDays) {
        int safeDays = Math.max(1, retentionDays);
        return !createdAt.plus(Duration.ofDays(safeDays)).isAfter(now);
    }

    private static Instant nextExecution(CronExpression expression, Instant now) {
        ZonedDateTime next = expression.next(now.atZone(ZONE_ID));
        return next == null ? null : next.toInstant();
    }

    private static Result requeueAt(Instant now, Instant next) {
        Duration delay = Duration.between(now, next);
        if (delay.isNegative() || delay.isZero()) {
            delay = Duration.ofSeconds(1);
        }
        return new Result(true, delay);
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new CronMemberSubmit()).workerCount(1).build();
    }
}
