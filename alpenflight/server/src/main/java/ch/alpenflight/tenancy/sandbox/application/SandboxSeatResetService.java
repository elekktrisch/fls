package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.tenancy.sandbox.SandboxSeeder;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SandboxSeatResetService {

    public record SeatResetSummary(int reclaimedExpiredSeatCount,
                                   int refreshedFreeSeatCount,
                                   int seatsThatKeptTheirLiveLeaseCount,
                                   int failedSeatCount) {

        @Override
        public String toString() {
            return reclaimedExpiredSeatCount + " expired seats reclaimed, "
                    + refreshedFreeSeatCount + " free seats re-seeded for the run date, "
                    + seatsThatKeptTheirLiveLeaseCount + " seats left on their live lease, "
                    + failedSeatCount + " seats failed";
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SandboxSeatResetService.class);

    private final DemoSeatRepository seats;
    private final DemoSeatLeaseService leases;
    private final DemoSeatLeaseProperties leaseProperties;
    private final SandboxClubPurge purge;
    private final SandboxSeeder seeder;
    private final Clock clock;

    public SandboxSeatResetService(DemoSeatRepository seats,
                                   DemoSeatLeaseService leases,
                                   DemoSeatLeaseProperties leaseProperties,
                                   SandboxClubPurge purge,
                                   SandboxSeeder seeder,
                                   Clock clock) {
        this.seats = seats;
        this.leases = leases;
        this.leaseProperties = leaseProperties;
        this.purge = purge;
        this.seeder = seeder;
        this.clock = clock;
    }

    public SeatResetSummary resetEverySeatThatHoldsNoLiveLease() {
        Instant now = Instant.now(clock);
        int reclaimed = 0;
        int refreshed = 0;
        int stillLeased = 0;
        int failed = 0;
        for (DemoSeat seat : seats.findAllInSeatNumberOrder()) {
            if (seat.getSeatNumber() > leaseProperties.poolSize()) {
                continue;
            }
            if (seat.holdsALiveLeaseAt(now)) {
                stillLeased++;
                continue;
            }
            boolean theVisitorLeftChangesBehind = seat.isReclaimableAt(now);
            if (!theVisitorLeftChangesBehind
                    && seeder.holdsTheSeedOfTheRunDate(seat.getClubId().value())) {
                continue;
            }
            try {
                deleteAndReSeedThenReturnToPool(
                        seat.getId(), seat.getClubId().value(), seat.getSeatNumber());
                if (theVisitorLeftChangesBehind) {
                    reclaimed++;
                } else {
                    refreshed++;
                }
            } catch (RuntimeException oneSeatThatMustNotStopTheOthers) {
                failed++;
                LOG.error("the sandbox reset failed for demo seat {} (club {}) — the seat keeps "
                                + "its state and the next run retries it",
                        seat.getSeatNumber(), seat.getClubId().value(),
                        oneSeatThatMustNotStopTheOthers);
            }
        }
        return new SeatResetSummary(reclaimed, refreshed, stillLeased, failed);
    }

    public boolean seedTheLeasedSeatUnlessItAlreadyHoldsTheSeedOfTheRunDate(UUID seatClubId,
                                                                           int seatNumber) {
        if (seatClubId == null) {
            throw new IllegalArgumentException("seatClubId must not be null");
        }
        if (seeder.holdsTheSeedOfTheRunDate(seatClubId)) {
            return false;
        }
        deleteAndReSeed(seatClubId, seatNumber);
        return true;
    }

    private void deleteAndReSeedThenReturnToPool(UUID seatId, UUID seatClubId, int seatNumber) {
        deleteAndReSeed(seatClubId, seatNumber);
        leases.returnSeatToPool(seatId);
    }

    private void deleteAndReSeed(UUID seatClubId, int seatNumber) {
        purge.deleteEveryRowOf(seatClubId);
        seeder.seed(seatClubId, seatNumber);
    }
}
