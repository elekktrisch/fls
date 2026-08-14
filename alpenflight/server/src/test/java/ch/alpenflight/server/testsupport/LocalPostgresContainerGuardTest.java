package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalPostgresContainerGuardTest {

    @Test
    void fires_when_container_mode_requested_and_ci_unset() {
        assertThat(PostgresTestContainerLifecycle.localContainerLaunchForbidden(
                        "jdbc:postgresql://lan/db", null, "1"))
                .as("FORCE_DOCKER on the dev box (CI unset) → guard fires")
                .isTrue();
    }

    @Test
    void fires_when_no_datasource_and_ci_unset() {
        assertThat(PostgresTestContainerLifecycle.localContainerLaunchForbidden(null, null, null))
                .as("no DATASOURCE_URL on the dev box (CI unset) also spins a local container → guard fires")
                .isTrue();
    }

    @Test
    void does_not_fire_in_external_mode() {
        assertThat(PostgresTestContainerLifecycle.localContainerLaunchForbidden(
                        "jdbc:postgresql://lan/db", null, null))
                .as("normal local external mode (LAN PG, no FORCE_DOCKER) → guard silent")
                .isFalse();
    }

    @Test
    void does_not_fire_when_ci_is_set() {
        assertThat(PostgresTestContainerLifecycle.localContainerLaunchForbidden(null, "true", "1"))
                .as("CI container mode is legitimate (the append-only role IT runs for real) → guard silent")
                .isFalse();
    }
}
