package ch.alpenflight.legacyextract;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

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
