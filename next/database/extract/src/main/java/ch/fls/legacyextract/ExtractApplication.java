package ch.fls.legacyextract;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * FLS legacy SQL Server metadata extractor — Spring Boot CLI entrypoint.
 *
 * <p><b>Source-of-truth precedence:</b> prod-applied DDL ({@code
 * INFORMATION_SCHEMA} + {@code sys.*}) is authoritative. {@code
 * flsserver/database/FLS/Updates/DBUpdate_v*.sql} reconstructs the same DDL
 * if you replay it. The EF migration tree at {@code
 * flsserver/src/FLS.Server.Data/Migrations/} is <b>frozen at 2015</b>
 * ({@code 201501222055041_InitialCreate}); EF and SQL disagree, the EF
 * mapping file scan emits drift records so consumers know which to trust.
 *
 * <p>Run from the module root: {@code ./gradlew bootRun --args="..."}. See
 * {@code README.md} for the full operator runbook.
 */
@SpringBootApplication
public class ExtractApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ExtractApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);

        int exitCode = 0;
        try {
            ApplicationArguments parsed = ctx.getBean(ApplicationArguments.class);
            Path defaultOut = Paths.get(System.getProperty("user.dir"), "raw");
            ExtractConfig config = CliRunner.parseConfig(parsed, defaultOut);

            String host = System.getenv().getOrDefault("MSSQL_HOST", "localhost");
            CliRunner.assertHostIsSafe(host, config.allowProd());

            MetadataExtractor extractor = ctx.getBean(MetadataExtractor.class);
            ExtractResult result = extractor.extractTo(config);

            System.out.println("[extract] wrote " + result.emittedFiles().size()
                    + " files to " + result.outDir()
                    + " in " + result.duration().toMillis() + " ms");
        } catch (RuntimeException e) {
            System.err.println("[extract] FAILED: " + e.getMessage());
            exitCode = 1;
        } finally {
            ctx.close();
        }
        System.exit(exitCode);
    }
}
