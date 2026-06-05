package ch.alpenflight.tenancy.showcase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Boots the {@link ShowcaseSeeder} once when the {@code showcase} profile is
 * active. Gated by {@code @Profile("showcase")} so it NEVER runs on the IT
 * bootstrap path (ADR 0021 — ITs stay lean) or in prod; it fires only for the
 * explicit on-demand local-dev / demo / e2e-display run.
 *
 * <p><strong>One-command invocation</strong> (from {@code alpenflight/server}):
 * <pre>{@code ./gradlew seedShowcase}</pre>
 * which is wired (server build) to run the app with
 * {@code --spring.profiles.active=dev,showcase} and
 * {@code --alpenflight.showcase.exit-after-seed=true} — so the seed commits and
 * the process exits (no long-running web server). Leaving the flag unset (e.g.
 * {@code ./gradlew bootRun --args='--spring.profiles.active=dev,showcase'})
 * seeds and then keeps the server up for an interactive dev session.
 */
@Component
@Profile("showcase")
public class ShowcaseSeedRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ShowcaseSeedRunner.class);

    private final ShowcaseSeeder seeder;
    private final ApplicationContext applicationContext;

    @Value("${alpenflight.showcase.exit-after-seed:false}")
    private boolean exitAfterSeed;

    public ShowcaseSeedRunner(ShowcaseSeeder seeder, ApplicationContext applicationContext) {
        this.seeder = seeder;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("showcase profile active — running the showcase seed loader");
        seeder.seed();
        if (exitAfterSeed) {
            LOG.info("showcase-seed: exit-after-seed=true — shutting down (one-command seed run)");
            // Graceful, deterministic exit code 0 — lets `./gradlew seedShowcase`
            // return rather than block on the web server.
            System.exit(SpringApplication.exit(applicationContext, () -> 0));
        }
    }
}
