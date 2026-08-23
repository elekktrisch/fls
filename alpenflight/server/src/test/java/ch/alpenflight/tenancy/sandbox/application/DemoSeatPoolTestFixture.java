package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class DemoSeatPoolTestFixture {

    private DemoSeatPoolTestFixture() {
    }

    static void returnEverySeatToThePool(DemoSeatRepository seats,
                                         PlatformTransactionManager transactionManager,
                                         Clock clock) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            for (DemoSeat seat : seats.findAllInSeatNumberOrder()) {
                seat.returnToPool(now);
                seats.save(seat);
            }
        });
    }

    static DemoSeat seatNumbered(DemoSeatRepository seats, int seatNumber) {
        return seats.findAllInSeatNumberOrder().stream()
                .filter(seat -> seat.getSeatNumber() == seatNumber)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no demo seat numbered " + seatNumber));
    }
}
