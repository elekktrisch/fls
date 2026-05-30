package ch.alpenflight.migrations.application;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Global ingest-throughput gate. Bounds the number of in-flight bundle
 * ingests to the configured permit count — a 2 GB body holds substantial
 * heap; the single-VPS footprint cannot afford unbounded concurrency.
 *
 * <p>Permit count is configurable via
 * {@code alpenflight.migration.ingest.global-permits} (default 1).
 * The IT profile overrides to 100 so the per-upload row-lock 409
 * ({@code BUNDLE_INGEST_IN_PROGRESS}) becomes testable without this
 * gate short-circuiting to 429 ({@code DATABASE_CAPACITY_EXCEEDED}).
 *
 * <p>Hoisted out of {@link MigrationBundleIngestService} so the bean is
 * injectable, swappable via {@code @TestConfiguration}, and unit-testable
 * without a Spring context.
 */
@Component
public class IngestConcurrencyGate {

    private final Semaphore permits;

    public IngestConcurrencyGate(
            @Value("${alpenflight.migration.ingest.global-permits:1}") int permitCount) {
        if (permitCount <= 0) {
            throw new IllegalArgumentException(
                    "global-permits must be > 0, got " + permitCount);
        }
        this.permits = new Semaphore(permitCount, true);
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}
