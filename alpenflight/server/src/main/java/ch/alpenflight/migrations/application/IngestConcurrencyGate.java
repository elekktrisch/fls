package ch.alpenflight.migrations.application;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
