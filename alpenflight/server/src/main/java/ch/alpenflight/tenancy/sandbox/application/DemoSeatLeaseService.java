package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException.Reason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableConfigurationProperties(DemoSeatLeaseProperties.class)
public class DemoSeatLeaseService {

    public record LeasedDemoSeat(
            UUID seatId,
            int seatNumber,
            ClubId clubId,
            String keycloakUsername,
            Instant leaseExpiresAt) {
    }

    private static final int CLAIM_ATTEMPTS_BEFORE_THE_POOL_COUNTS_AS_TOO_BUSY = 24;

    private final DemoSeatRepository seats;
    private final DemoSeatLeaseProperties leaseProperties;
    private final Clock clock;
    private final TransactionTemplate oneTransactionPerClaimAttempt;

    public DemoSeatLeaseService(DemoSeatRepository seats,
                                DemoSeatLeaseProperties leaseProperties,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.seats = seats;
        this.leaseProperties = leaseProperties;
        this.clock = clock;
        this.oneTransactionPerClaimAttempt = new TransactionTemplate(transactionManager);
        this.oneTransactionPerClaimAttempt.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LeasedDemoSeat leaseFreeSeatFor(String visitorAddress) {
        String address = requireAnAddress(visitorAddress);
        for (int attempt = 0;
                attempt < CLAIM_ATTEMPTS_BEFORE_THE_POOL_COUNTS_AS_TOO_BUSY;
                attempt++) {
            LeasedDemoSeat granted = claimOneFreeSeatUnlessAnotherVisitorTookItFirst(
                    address, attempt);
            if (granted != null) {
                return granted;
            }
        }
        throw new NoDemoSeatAvailableException(
                Reason.TOO_MANY_VISITORS_CLAIMED_A_SEAT_AT_THE_SAME_MOMENT);
    }

    private @Nullable LeasedDemoSeat claimOneFreeSeatUnlessAnotherVisitorTookItFirst(
            String address, int preferredFreeSeatIndex) {
        try {
            return claimOneFreeSeatInItsOwnTransaction(address, preferredFreeSeatIndex);
        } catch (OptimisticLockingFailureException anotherVisitorClaimedTheSameSeatFirst) {
            return null;
        }
    }

    private LeasedDemoSeat claimOneFreeSeatInItsOwnTransaction(String address,
                                                              int preferredFreeSeatIndex) {
        LeasedDemoSeat granted = oneTransactionPerClaimAttempt.execute(status -> {
            Instant now = Instant.now(clock);
            List<DemoSeat> pool = seatsInsideTheConfiguredPoolSize();
            refuseWhenTheAddressAlreadyHoldsItsCap(pool, address, now);
            DemoSeat free = freeSeatFrom(pool, preferredFreeSeatIndex);
            free.leaseTo(address, now, leaseProperties.leaseIdlePeriod());
            return describe(seats.save(free));
        });
        if (granted == null) {
            throw new IllegalStateException("the demo seat claim transaction returned no result");
        }
        return granted;
    }

    private List<DemoSeat> seatsInsideTheConfiguredPoolSize() {
        return seats.findAllInSeatNumberOrder().stream()
                .filter(seat -> seat.getSeatNumber() <= leaseProperties.poolSize())
                .toList();
    }

    private void refuseWhenTheAddressAlreadyHoldsItsCap(List<DemoSeat> pool,
                                                        String address,
                                                        Instant now) {
        long liveSeatsThisAddressHolds = pool.stream()
                .filter(seat -> seat.holdsALiveLeaseFor(address, now))
                .count();
        if (liveSeatsThisAddressHolds >= leaseProperties.maxLiveSeatsPerAddress()) {
            throw new NoDemoSeatAvailableException(
                    Reason.THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT);
        }
    }

    private static DemoSeat freeSeatFrom(List<DemoSeat> pool, int preferredFreeSeatIndex) {
        List<DemoSeat> freeSeats = pool.stream().filter(DemoSeat::isFree).toList();
        if (freeSeats.isEmpty()) {
            throw new NoDemoSeatAvailableException(Reason.EVERY_DEMO_SEAT_IS_IN_USE);
        }
        return freeSeats.get(preferredFreeSeatIndex % freeSeats.size());
    }

    private static LeasedDemoSeat describe(DemoSeat claimed) {
        Instant leaseExpiresAt = claimed.getLeaseExpiresAt();
        if (leaseExpiresAt == null) {
            throw new IllegalStateException(
                    "demo seat " + claimed.getSeatNumber() + " carries no lease expiry after a "
                            + "successful claim");
        }
        return new LeasedDemoSeat(
                claimed.getId(),
                claimed.getSeatNumber(),
                claimed.getClubId(),
                claimed.getKeycloakUsername(),
                leaseExpiresAt);
    }

    private static String requireAnAddress(String visitorAddress) {
        String trimmed = visitorAddress == null ? "" : visitorAddress.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("visitorAddress must not be blank");
        }
        return trimmed;
    }
}
