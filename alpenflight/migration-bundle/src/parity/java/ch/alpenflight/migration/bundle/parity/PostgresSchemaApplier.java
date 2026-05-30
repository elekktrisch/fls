package ch.alpenflight.migration.bundle.parity;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/**
 * Bridges the parity harness's per-class Postgres container to the
 * alpenflight server's Flyway migration set. The migrations live under
 * {@code ../server/src/main/resources/db/migration/} relative to the
 * migration-bundle module — this class resolves that path from the working
 * directory so the harness boots whether invoked from the bundle module or
 * the repository root.
 *
 * <p>The container starts fresh per parity-job run ({@code withReuse(false)})
 * so a successful migrate is idempotent in the only sense that matters:
 * always V1..VN against an empty database.
 */
public final class PostgresSchemaApplier {

    /**
     * Operator-keycloak-sub placeholder consumed by V14. Pin to a known
     * non-real UUID so the harness boots without environment dependencies.
     * Mirrors the dev placeholder used in {@code alpenflight/server/build.gradle.kts}.
     */
    private static final String OPERATOR_KEYCLOAK_SUB_PLACEHOLDER =
            "00000000-0000-0000-0000-0000000000ff";

    private PostgresSchemaApplier() { }

    public static void apply(String jdbcUrl, String user, String password) {
        Path migrationLocation = resolveMigrationLocation();
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("filesystem:" + migrationLocation.toAbsolutePath())
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .placeholders(java.util.Map.of(
                        "alpenflight.operator.keycloak_sub", OPERATOR_KEYCLOAK_SUB_PLACEHOLDER))
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
