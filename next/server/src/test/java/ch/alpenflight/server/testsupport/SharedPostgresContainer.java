package ch.alpenflight.server.testsupport;

/**
 * JVM-wide singleton Postgres test container. One container is started lazily
 * on first reference and lives for the duration of the JVM (shutdown hook in
 * {@link PostgresTestContainerLifecycle#start()} tears it down).
 *
 * <p>Every {@code @SpringBootTest} that needs a real database wires to
 * {@link #INSTANCE} via {@code @DynamicPropertySource}. Sharing one container
 * across the whole test suite:
 * <ul>
 *   <li>Eliminates 5-10s of per-class container startup overhead.</li>
 *   <li>Lets Spring's context cache hit across classes that register
 *       identical datasource properties.</li>
 *   <li>Makes Flyway's {@code migrate()} idempotent across classes — V1+V2
 *       are applied once; subsequent class boots see the schema already in
 *       place via {@code flyway_schema_history}.</li>
 * </ul>
 *
 * <p>Replaced the prior per-class-static pattern + the H2-with-MODE=PostgreSQL
 * fallback in {@code application-test.yml}. H2 even in PG-compat mode could
 * not parse {@code TEXT[]}, partial indexes, or {@code COMMENT ON COLUMN};
 * the test profile no longer needs a portable schema target — every test
 * exercises the production database engine.
 *
 * <p>Tests still mark themselves {@code @EnabledIf("dockerAvailable")} so
 * a contributor without Docker can run {@code ./gradlew check} cleanly —
 * tests skip rather than fail. The condition is the {@link #AVAILABLE} flag.
 */
public final class SharedPostgresContainer {

    public static final PostgresTestContainerLifecycle INSTANCE = new PostgresTestContainerLifecycle();
    private static final boolean AVAILABLE = tryStart();

    private SharedPostgresContainer() {}

    /**
     * JUnit Jupiter {@code @EnabledIf} hook — every DB-dependent {@code @SpringBootTest}
     * class references this method via {@code @EnabledIf("ch.alpenflight.server.testsupport.SharedPostgresContainer#available")}
     * so the per-class {@code dockerAvailable()} boilerplate stays in one place.
     *
     * <p><strong>CI fail-loud guard.</strong> If the container failed to start AND
     * the {@code CI} env var is set (GitHub Actions / GitLab / CircleCI / etc.),
     * throw instead of returning {@code false}. Otherwise CI would happily skip
     * every DB-touching test on a transient Docker daemon hiccup and report
     * green with N silently-skipped specs — exactly the kind of false-pass this
     * gate is supposed to prevent. Dev machines (no {@code CI} env var) keep the
     * graceful-skip behavior so a contributor without Docker can still run
     * {@code ./gradlew check}.
     */
    public static boolean available() {
        if (!AVAILABLE && System.getenv("CI") != null) {
            throw new IllegalStateException(
                    "Docker is required for DB integration tests in CI but the test container "
                            + "did not start. See the SharedPostgresContainer startup error above "
                            + "(stderr) and check the runner's Docker daemon (`docker info`).");
        }
        return AVAILABLE;
    }

    private static boolean tryStart() {
        try {
            INSTANCE.start();
            return true;
        } catch (Throwable t) {
            System.err.println("""
                    [alpenflight-server] Skipping DB-dependent tests — Docker unreachable.
                      Root cause: %s
                      Start Docker Desktop / Docker Engine and re-run.
                    """.formatted(t.getMessage()));
            return false;
        }
    }
}
