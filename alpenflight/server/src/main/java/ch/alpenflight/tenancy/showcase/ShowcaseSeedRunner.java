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
            System.exit(SpringApplication.exit(applicationContext, () -> 0));
        }
    }
}
