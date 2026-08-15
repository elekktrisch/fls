package ch.alpenflight.legacyextract;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.ApplicationArguments;

public final class CliRunner {

    private CliRunner() {}

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

    public static void assertHostIsSafe(String host, boolean allowProd) {
        if (isLoopback(host)) return;
        if (!allowProd) {
            throw new IllegalStateException(
                    "non-loopback MSSQL_HOST '" + host + "' requires --allow-prod to confirm operator intent");
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalizedHost = host.trim().toLowerCase();
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("::1")
                || normalizedHost.startsWith("127.")
                || normalizedHost.equals("0:0:0:0:0:0:0:1");
    }
}
