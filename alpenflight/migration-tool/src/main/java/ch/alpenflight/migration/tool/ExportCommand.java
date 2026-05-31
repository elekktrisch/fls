package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.TinkMigrationBundleCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The standalone legacy-FLS export entry point (S-139). Reads a legacy MSSQL
 * instance read-only and emits an ALPF-encrypted migration bundle the server
 * decrypts byte-for-byte.
 *
 * <p>Entity selection is driven off {@link KnownMappers#all()} ∩
 * {@link MapperLegacyBindings#isRegistered}: the jar exports exactly the
 * entities the SELECT registry binds today (5 in the slice) and grows
 * automatically as S-187a extends the registry — no code change here.
 *
 * <p>The DB password is NEVER an argv option (process listing leak). It is
 * resolved, in order, from {@code --password-env}'s named env var, an
 * interactive console prompt, or stdin.
 */
@Command(
        name = "alpenflight-export",
        mixinStandardHelpOptions = true,
        version = "alpenflight-export (S-139 vertical slice)",
        description = "Export a legacy FLS MSSQL database into an ALPF-encrypted migration bundle.")
public final class ExportCommand implements Callable<Integer> {

    @Option(names = "--jdbc-url", required = true,
            description = "Legacy SQL Server JDBC URL. ApplicationIntent=ReadOnly is forced on.")
    String jdbcUrl;

    @Option(names = "--user", description = "Legacy database user.")
    String user;

    @Option(names = "--handshake-file",
            description = "S-140a handshake JSON (uploadId + RSA-4096 publicKeyPem). "
                    + "Required unless --dry-run.")
    Path handshakeFile;

    @Option(names = "--output",
            description = "Output bundle path. Default: ./alpenflight-export-<timestamp>.enc")
    Path output;

    @Option(names = "--force", description = "Overwrite an existing --output file.")
    boolean force;

    @Option(names = "--verbose", description = "Print per-entity row counts + sha256 to stderr.")
    boolean verbose;

    @Option(names = "--dry-run",
            description = "Read + hash pass only: print stats, write nothing. Needs no handshake.")
    boolean dryRun;

    @Option(names = "--password-env",
            description = "Name of the env var holding the DB password "
                    + "(default ALPF_DB_PASSWORD). Never pass the password as an argument.")
    String passwordEnv = "ALPF_DB_PASSWORD";

    @Override
    public Integer call() {
        try {
            return run().code();
        } catch (ExportException e) {
            System.err.println("ERROR: " + e.getMessage());
            return e.exitCode().code();
        }
    }

    private ExitCode run() {
        List<EntityType> entities = registeredEntities();
        if (entities.isEmpty()) {
            throw new ExportException(ExitCode.USAGE,
                    "No entities are both KnownMappers-registered and bound in "
                            + "MapperLegacyBindings — nothing to export.");
        }

        // Validate the public key BEFORE connecting (story AC).
        HandshakeFile handshake = null;
        if (!dryRun) {
            if (handshakeFile == null) {
                throw new ExportException(ExitCode.USAGE,
                        "--handshake-file is required unless --dry-run is set.");
            }
            handshake = HandshakeFile.parse(handshakeFile);
        }

        Path resolvedOutput = dryRun ? null : resolveOutput();
        if (resolvedOutput != null && Files.exists(resolvedOutput) && !force) {
            throw new ExportException(ExitCode.OUTPUT_EXISTS,
                    "Output file already exists (use --force to overwrite): " + resolvedOutput);
        }

        char[] password = resolvePassword();
        Path workDir = createWorkDir();
        List<Path> tempFiles = new ArrayList<>();
        try (LegacyJdbcReader reader = LegacyJdbcReader.open(jdbcUrl, user, password)) {
            wipe(password);
            BundleWriter writer = new BundleWriter(reader, workDir, verbose);
            System.err.println("Streaming " + entities.size() + " registered entit"
                    + (entities.size() == 1 ? "y" : "ies") + "...");
            List<EntityStreamResult> results = writer.streamEntities(entities);
            for (EntityStreamResult r : results) {
                tempFiles.add(r.ndjsonTempFile());
            }
            long totalRows = results.stream().mapToLong(EntityStreamResult::rowCount).sum();

            if (dryRun) {
                System.out.println("DRY RUN: " + results.size() + " entities, "
                        + totalRows + " rows total. No bundle written.");
                return ExitCode.OK;
            }

            String deploymentName = "legacy-export-" + timestamp();
            ManifestModel manifest = ManifestBuilder.build(reader, entities, deploymentName);
            byte[] manifestBytes = new ObjectMapper().writeValueAsBytes(manifest);

            Path plaintext = Files.createTempFile(workDir, "alpf-bundle-", ".tar.gz");
            tempFiles.add(plaintext);
            writer.assembleTarGz(manifestBytes, results, plaintext);

            MigrationBundleCipher cipher = new TinkMigrationBundleCipher();
            new BundleEncryptor(cipher).encryptTo(
                    resolvedOutput, plaintext, handshake.uploadId(), handshake.publicKeyDer());

            System.out.println("Wrote encrypted bundle: " + resolvedOutput
                    + " (" + results.size() + " entities, " + totalRows + " rows, "
                    + manifest.clubs().size() + " club(s))");
            return ExitCode.OK;
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Export failed: " + e.getMessage(), e);
        } finally {
            wipe(password);
            tempFiles.forEach(BundleWriter::deleteQuietly);
            BundleWriter.deleteQuietly(workDir);
        }
    }

    /** {@link KnownMappers} ∩ registered bindings, in registry order. */
    static List<EntityType> registeredEntities() {
        List<EntityType> entities = new ArrayList<>();
        for (Mapper mapper : KnownMappers.all()) {
            EntityType type = mapper.entityType();
            if (MapperLegacyBindings.isRegistered(type)) {
                entities.add(type);
            }
        }
        return entities;
    }

    private Path resolveOutput() {
        if (output != null) {
            return output.toAbsolutePath();
        }
        return Path.of("alpenflight-export-" + timestamp() + ".enc").toAbsolutePath();
    }

    private char[] resolvePassword() {
        String fromEnv = System.getenv(passwordEnv);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv.toCharArray();
        }
        Console console = System.console();
        if (console != null) {
            char[] entered = console.readPassword("Legacy DB password: ");
            if (entered != null) {
                return entered;
            }
        }
        // Non-interactive (piped) stdin fallback — read one line.
        try {
            BufferedReader stdin = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = stdin.readLine();
            if (line != null) {
                return line.toCharArray();
            }
        } catch (IOException ignored) {
            // fall through to the error below
        }
        throw new ExportException(ExitCode.USAGE,
                "No DB password available: set $" + passwordEnv
                        + ", run interactively, or pipe it on stdin.");
    }

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("alpf-export-");
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Cannot create work directory: " + e.getMessage(), e);
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new ExportCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exit);
    }
}
