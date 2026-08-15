package ch.alpenflight.migration.bundle.parity;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ParityRunIdentity {

    private final String gitShortSha;
    private final long seed;
    private final int scale;

    public ParityRunIdentity(String gitShortSha, long seed, int scale) {
        this.gitShortSha = gitShortSha;
        this.seed = seed;
        this.scale = scale;
    }

    public static ParityRunIdentity fromSystemProperties() {
        long seed = Long.getLong("parity.seed", 42L);
        int scale = Integer.getInteger("parity.scale", 1);
        return new ParityRunIdentity(readGitShortSha(), seed, scale);
    }

    public long seed() {
        return seed;
    }

    public int scale() {
        return scale;
    }

    public String runId() {
        return gitShortSha + "-" + seed + "-" + scale;
    }

    public Path reportsDirectory(Path projectBuildDirectory) {
        return projectBuildDirectory.resolve("reports").resolve("parity").resolve(runId());
    }

    private static String readGitShortSha() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "git", "rev-parse", "--short=7", "HEAD");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                return "nogit";
            }
            String trimmed = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            return trimmed.isEmpty() ? "nogit" : trimmed;
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return "nogit";
        }
    }

    public static Path defaultProjectBuildDirectory() {
        return Paths.get("build");
    }
}
