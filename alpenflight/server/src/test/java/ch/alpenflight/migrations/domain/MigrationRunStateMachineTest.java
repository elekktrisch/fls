package ch.alpenflight.migrations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MigrationRun} state machine transitions. */
class MigrationRunStateMachineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC);
    private final UUID id = UUID.fromString("019e30c3-2c00-7001-8000-000000000041");
    private final UUID uploadId = UUID.fromString("019e30c3-2c00-7001-8000-000000000042");

    @Test
    void start_creates_run_in_decrypting_with_empty_warnings() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);

        assertThat(run.getId()).isEqualTo(id);
        assertThat(run.getUploadId()).isEqualTo(uploadId);
        assertThat(run.getState()).isEqualTo(MigrationRunState.DECRYPTING);
        assertThat(run.getStartedAt()).isEqualTo(Instant.parse("2026-05-30T12:00:00Z"));
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getWarningsJson()).isEqualTo("[]");
    }

    @Test
    void happy_path_walks_decrypting_to_completed() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);

        run.transitionTo(MigrationRunState.PROVISIONING);
        UUID deploymentId = UUID.randomUUID();
        run.attachDeployment(deploymentId);

        run.transitionTo(MigrationRunState.INGESTING);
        run.noteCurrent("FLIGHT", UUID.randomUUID());

        run.transitionTo(MigrationRunState.COMPLETING);
        run.markCompleted(clock);

        assertThat(run.getState()).isEqualTo(MigrationRunState.COMPLETED);
        assertThat(run.getDeploymentId()).isEqualTo(deploymentId);
        assertThat(run.getCompletedAt()).isEqualTo(Instant.parse("2026-05-30T12:00:00Z"));
        assertThat(run.getCurrentEntity()).isNull();
        assertThat(run.getCurrentClubId()).isNull();
    }

    @Test
    void failed_terminal_rejects_further_transitions() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);
        run.markFailed("BUNDLE_HEADER_MALFORMED", "magic mismatch", clock);

        assertThat(run.getState()).isEqualTo(MigrationRunState.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("BUNDLE_HEADER_MALFORMED");

        assertThatExceptionOfType(IllegalRunStateException.class)
                .isThrownBy(() -> run.transitionTo(MigrationRunState.PROVISIONING));
    }

    @Test
    void illegal_edge_throws_with_state_pair_in_message() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);

        assertThatExceptionOfType(IllegalRunStateException.class)
                .isThrownBy(() -> run.transitionTo(MigrationRunState.COMPLETING))
                .withMessageContaining("DECRYPTING")
                .withMessageContaining("COMPLETING");
    }

    @Test
    void noteCurrent_outside_ingesting_throws() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);

        assertThatExceptionOfType(IllegalRunStateException.class)
                .isThrownBy(() -> run.noteCurrent("FLIGHT", null));
    }

    @Test
    void attachDeployment_outside_provisioning_throws() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);

        assertThatExceptionOfType(IllegalRunStateException.class)
                .isThrownBy(() -> run.attachDeployment(UUID.randomUUID()));
    }

    @Test
    void replaceWarningsJson_treats_blank_as_empty_array() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);
        run.replaceWarningsJson("  ");
        assertThat(run.getWarningsJson()).isEqualTo("[]");

        run.replaceWarningsJson("[{\"code\":\"FOO\"}]");
        assertThat(run.getWarningsJson()).isEqualTo("[{\"code\":\"FOO\"}]");
    }

    @Test
    void markFailed_requires_non_blank_error_code() {
        MigrationRun run = MigrationRun.start(id, uploadId, clock);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> run.markFailed("  ", null, clock));
    }
}
