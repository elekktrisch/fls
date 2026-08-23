package ch.alpenflight.tenancy.sandbox.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public record DemoSeatLeaseProperties(
        int poolSize,
        int maxLiveSeatsPerAddress,
        Duration leaseIdlePeriod) {

    public DemoSeatLeaseProperties {
        if (poolSize < 1) {
            throw new IllegalArgumentException("demo.pool-size must be at least 1, was " + poolSize);
        }
        if (maxLiveSeatsPerAddress < 1) {
            throw new IllegalArgumentException(
                    "demo.max-live-seats-per-address must be at least 1, was "
                            + maxLiveSeatsPerAddress);
        }
        if (leaseIdlePeriod == null || leaseIdlePeriod.isZero() || leaseIdlePeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "demo.lease-idle-period must be a positive duration, was " + leaseIdlePeriod);
        }
    }
}
