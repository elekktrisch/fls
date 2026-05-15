package ch.fls.legacyextract;

import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;

/**
 * Translates {@link ApplicationArguments} into an {@link ExtractConfig} and
 * runs the operator-safety preflight (loopback check, --allow-prod gate).
 */
public final class CliRunner {

    private CliRunner() {}

    /**
     * Parse CLI args into an extraction config. {@code defaultOutDir} is used
     * when the operator hasn't passed {@code --out-dir=...}.
     */
    public static ExtractConfig parseConfig(ApplicationArguments args, Path defaultOutDir) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Assert the connection target is safe to extract from given the operator
     * flag. Loopback hosts ({@code localhost}, {@code 127.0.0.1}, {@code ::1})
     * are always safe. Non-loopback hosts require {@code --allow-prod}.
     */
    public static void assertHostIsSafe(String host, boolean allowProd) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
