package ch.alpenflight.migration.bundle.parity;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

public final class PostgresSchemaApplier {

    private static final java.util.Map<String, String>
            FLYWAY_PLACEHOLDERS_MIRRORING_APPLICATION_TEST_YML = java.util.Map.of(
                    "alpenflight.operator.keycloak_sub", "00000000-0000-0000-0000-0000000000ff",
                    "app_role_password", "alpenflight_app");

    private PostgresSchemaApplier() { }

    public static void apply(String jdbcUrl, String user, String password) {
        Path migrationLocation = resolveMigrationLocation();
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("filesystem:" + migrationLocation.toAbsolutePath())
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .placeholders(FLYWAY_PLACEHOLDERS_MIRRORING_APPLICATION_TEST_YML)
                .load();
        flyway.migrate();
    }

    private static Path resolveMigrationLocation() {
        Path[] candidates = new Path[] {
                Path.of("alpenflight/server/src/main/resources/db/migration"),
                Path.of("../server/src/main/resources/db/migration"),
                Path.of("server/src/main/resources/db/migration"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Cannot locate alpenflight server Flyway migrations. Tried: "
                        + java.util.Arrays.toString(candidates)
                        + " from cwd=" + Path.of("").toAbsolutePath());
    }
}
