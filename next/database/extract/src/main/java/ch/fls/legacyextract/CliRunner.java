package ch.fls.legacyextract;

import java.nio.file.Path;
import java.nio.file.Paths;
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
        boolean aggregate = args.containsOption("allow-aggregate-counts");
        boolean prod = args.containsOption("allow-prod");
        Path outDir = defaultOutDir;
        if (args.containsOption("out-dir")) {
            var values = args.getOptionValues("out-dir");
            if (values != null && !values.isEmpty()) {
                outDir = Paths.get(values.get(0));
            }
        }
        return new ExtractConfig(aggregate, prod, outDir);
    }

    /**
     * Assert the connection target is safe to extract from given the operator
     * flag. Loopback hosts ({@code localhost}, {@code 127.0.0.1}, {@code ::1})
     * are always safe. Non-loopback hosts require {@code --allow-prod}.
     */
    public static void assertHostIsSafe(String host, boolean allowProd) {
        if (isLoopback(host)) return;
        if (!allowProd) {
            throw new IllegalStateException(
                    "non-loopback MSSQL_HOST '" + host + "' requires --allow-prod to confirm operator intent");
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return h.equals("localhost")
                || h.equals("127.0.0.1")
                || h.equals("::1")
                || h.startsWith("127.")
                || h.equals("0:0:0:0:0:0:0:1");
    }
}
