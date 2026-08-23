package ch.alpenflight.tenancy.sandbox.application;

import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.returnEverySeatToThePool;
import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.seatNumbered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.tenancy.sandbox.application.DemoSeatLeaseService.LeasedDemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException.Reason;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@TestPropertySource(properties = {
        "demo.lease-idle-period=PT2S",
        "spring.datasource.hikari.maximum-pool-size=16"
})
class DemoSeatLeaseServiceIT extends PostgresIntegrationTest {

    private static final String ADDRESS_THAT_HOLDS_NO_SEAT_YET = "203.0.113.250";

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

    @Test
    void parallel_lease_calls_hand_out_one_distinct_seat_per_visitor() throws Exception {
        int visitors = leaseProperties.poolSize();
        CyclicBarrier everyVisitorClaimsAtTheSameMoment = new CyclicBarrier(visitors);
        ExecutorService oneThreadPerVisitor = Executors.newFixedThreadPool(visitors);
        List<Future<LeasedDemoSeat>> claims = new ArrayList<>();

        for (int visitor = 0; visitor < visitors; visitor++) {
            String ownAddress = "203.0.113." + visitor;
            claims.add(oneThreadPerVisitor.submit(() -> {
                everyVisitorClaimsAtTheSameMoment.await(30, TimeUnit.SECONDS);
                return leases.leaseFreeSeatFor(ownAddress);
            }));
        }
        oneThreadPerVisitor.shutdown();

        List<Integer> handedOutSeatNumbers = new ArrayList<>();
        for (Future<LeasedDemoSeat> claim : claims) {
            handedOutSeatNumbers.add(claim.get(60, TimeUnit.SECONDS).seatNumber());
        }
        assertThat(handedOutSeatNumbers)
                .hasSize(visitors)
                .doesNotHaveDuplicates();
    }

    @Test
    void a_second_lease_from_the_same_address_is_refused_at_the_production_default_cap_of_one() {
        assertThat(leaseProperties.maxLiveSeatsPerAddress()).isEqualTo(1);
        String oneAddress = "203.0.113.201";
        leases.leaseFreeSeatFor(oneAddress);

        NoDemoSeatAvailableException refused = assertThrows(NoDemoSeatAvailableException.class,
                () -> leases.leaseFreeSeatFor(oneAddress));

        assertThat(refused.reason())
                .isEqualTo(Reason.THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT);
        assertThat(refused.readableReason()).isNotBlank();
    }

    @Test
    void the_pool_refuses_one_more_visitor_when_every_seat_is_leased() {
        for (int visitor = 0; visitor < leaseProperties.poolSize(); visitor++) {
            leases.leaseFreeSeatFor("203.0.113." + visitor);
        }

        NoDemoSeatAvailableException refused = assertThrows(NoDemoSeatAvailableException.class,
                () -> leases.leaseFreeSeatFor(ADDRESS_THAT_HOLDS_NO_SEAT_YET));

        assertThat(refused.reason()).isEqualTo(Reason.EVERY_DEMO_SEAT_IS_IN_USE);
        assertThat(refused.readableReason()).isNotBlank();
    }

    @Test
    void an_idle_lease_becomes_reclaimable_and_stops_counting_against_the_address_cap()
            throws InterruptedException {
        String oneAddress = "203.0.113.202";
        LeasedDemoSeat first = leases.leaseFreeSeatFor(oneAddress);

        Thread.sleep(leaseProperties.leaseIdlePeriod().toMillis() + 500);
        Instant afterTheIdlePeriod = Instant.now(clock);

        DemoSeat idle = seatNumbered(seats, first.seatNumber());
        assertThat(idle.holdsALiveLeaseAt(afterTheIdlePeriod)).isFalse();
        assertThat(idle.isReclaimableAt(afterTheIdlePeriod)).isTrue();
        assertThat(idle.getLeaseState()).isEqualTo(DemoSeat.LeaseState.LEASED);

        LeasedDemoSeat afterTheLeaseWentIdle = leases.leaseFreeSeatFor(oneAddress);

        assertThat(afterTheLeaseWentIdle.seatNumber()).isNotEqualTo(first.seatNumber());
    }
}
