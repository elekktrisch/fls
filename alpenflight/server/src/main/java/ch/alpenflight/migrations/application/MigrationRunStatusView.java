package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.MigrationRun;
import ch.alpenflight.migrations.domain.MigrationRunState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MigrationRunStatusView(
        UUID uploadId,
        MigrationRunState state,
        @Nullable String currentEntity,
        @Nullable UUID currentClubId,
        @Nullable UUID deploymentId,
        List<UUID> clubIds,
        @Nullable String errorCode,
        Instant occurredAt) {

    public MigrationRunStatusView {
        clubIds = List.copyOf(clubIds);
    }

    public static MigrationRunStatusView of(MigrationRun run, List<UUID> clubIds) {
        Instant occurredAt = run.getCompletedAt() != null ? run.getCompletedAt() : run.getStartedAt();
        return new MigrationRunStatusView(
                run.getUploadId(),
                run.getState(),
                run.getCurrentEntity(),
                run.getCurrentClubId(),
                run.getDeploymentId(),
                clubIds,
                run.getErrorCode(),
                occurredAt);
    }
}
