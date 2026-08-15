package ch.alpenflight.server.testsupport;

public final class SharedPostgresContainer {

    public static final PostgresTestContainerLifecycle INSTANCE = new PostgresTestContainerLifecycle();
    private static final boolean AVAILABLE = tryStart();

    private SharedPostgresContainer() {}

    public static boolean available() {
        if (!AVAILABLE && System.getenv("CI") != null) {
            throw new IllegalStateException(
                    "Docker is required for DB integration tests in CI but the test container "
                            + "did not start. See the SharedPostgresContainer startup error above "
                            + "(stderr) and check the runner's Docker daemon (`docker info`).");
        }
        if (!AVAILABLE && PostgresTestContainerLifecycle.externalConfigured()) {
            throw new IllegalStateException(
                    "External-PG test mode is configured (DATASOURCE_URL) but the external test "
                            + "database did not come up: " + STARTUP_ERROR);
        }
        return AVAILABLE;
    }

    private static volatile String STARTUP_ERROR = "(no startup error recorded)";

    private static boolean tryStart() {
        try {
            INSTANCE.start();
            return true;
        } catch (Throwable t) {
            STARTUP_ERROR = t.getMessage();
            System.err.println("""
                    [alpenflight-server] Skipping DB-dependent tests — database unreachable.
                      Root cause: %s
                      Start Docker Desktop / Docker Engine and re-run.
                    """.formatted(t.getMessage()));
            return false;
        }
    }
}
