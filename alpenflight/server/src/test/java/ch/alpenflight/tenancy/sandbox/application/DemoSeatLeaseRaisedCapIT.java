package ch.alpenflight.tenancy.sandbox.application;

import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.returnEverySeatToThePool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.tenancy.sandbox.application.DemoSeatLeaseService.LeasedDemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException.Reason;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@ActiveProfiles("dev")
class DemoSeatLeaseRaisedCapIT extends PostgresIntegrationTest {

    @Autowired
    private DemoSeatLeaseService leases;

    @Autowired
    private DemoSeatRepository seats;

    @Autowired
    private DemoSeatLeaseProperties leaseProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @BeforeEach
    void everySeatStartsFree() {
        returnEverySeatToThePool(seats, transactionManager, clock);
    }

    @AfterEach
    void everySeatGoesBackSoTheNextTestClassReadsThePoolAsFlywayCreatedIt() {
        returnEverySeatToThePool(seats, transactionManager, clock);
    }

    @Test
    void the_end_to_end_profile_raises_the_cap_above_the_production_default_of_one() {
        assertThat(leaseProperties.maxLiveSeatsPerAddress()).isGreaterThan(1);
        assertThat(leaseProperties.maxLiveSeatsPerAddress())
                .isLessThanOrEqualTo(leaseProperties.poolSize());
    }

    @Test
    void one_address_holds_the_raised_cap_of_live_seats_and_is_refused_one_past_it() {
        String oneAddress = "203.0.113.210";
        List<Integer> handedOutSeatNumbers = new ArrayList<>();
        for (int seat = 0; seat < leaseProperties.maxLiveSeatsPerAddress(); seat++) {
            LeasedDemoSeat granted = leases.leaseFreeSeatFor(oneAddress);
            handedOutSeatNumbers.add(granted.seatNumber());
        }

        NoDemoSeatAvailableException refused = assertThrows(NoDemoSeatAvailableException.class,
                () -> leases.leaseFreeSeatFor(oneAddress));

        assertThat(handedOutSeatNumbers)
                .hasSize(leaseProperties.maxLiveSeatsPerAddress())
                .doesNotHaveDuplicates();
        assertThat(refused.reason())
                .isEqualTo(Reason.THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT);
    }
}
