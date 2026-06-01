package ch.alpenflight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link EnableScheduling} turns on Spring's {@code @Scheduled} runner so
 * server-side periodic work (S-140's hourly handshake-TTL sweep is the
 * first job to land) executes. The S-137 {@code LifecycleStateFilterAspect}
 * is the per-(Deployment, Club) variant for tenant-scoped jobs; pre-tenant
 * jobs declare {@link ch.alpenflight.platform.scheduling.UnscopedScheduledJob}
 * instead so the ArchUnit coverage rule still passes.
 */
@SpringBootApplication
@EnableScheduling
public class AlpenFlightApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlpenFlightApplication.class, args);
    }
}
