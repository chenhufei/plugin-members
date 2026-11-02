package run.halo.members;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "member.plugin.halo.run",
    version = "v1alpha1",
    kind = "CronMemberSubmit",
    plural = "cronmembersubmits",
    singular = "cronmembersubmit"
)
public class CronMemberSubmit extends AbstractExtension {

    private Spec spec = new Spec();
    private Status status = new Status();

    @Data
    public static class Spec {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String cron = "@daily";

        private boolean enabled;
        private boolean cleanupRejected = true;
        private int rejectedRetentionDays = 30;
        private boolean cleanupPending;
        private int pendingRetentionDays = 90;
    }

    @Data
    public static class Status {
        private Instant lastScheduledTimestamp;
        private Instant nextSchedulingTimestamp;
        private Integer lastCleanedCount;
        private String lastMessage;
    }
}
