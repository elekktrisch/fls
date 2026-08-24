package ch.alpenflight.tenancy.sandbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoSeatPrincipalBindingTest {

    private static final UUID SEAT_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-0000000de001");
    private static final UUID A_REAL_CLUB = UUID.fromString("019e2e15-2c00-7c00-8000-000000000c01");
    private static final String SEAT_USERNAME = "demo1";
    private static final String A_PRINCIPAL_THAT_OWNS_NO_SEAT = "real-club-administrator";

    private final DemoSeatPoolThatFlywaySeedsAfterTheFirstRead seats =
            new DemoSeatPoolThatFlywaySeedsAfterTheFirstRead();
    private final DemoSeatPrincipalBinding binding = new DemoSeatPrincipalBinding(seats);

    @Test
    void a_request_that_reads_the_pool_before_it_holds_a_seat_does_not_disable_the_binding() {
        binding.refusesPrincipalCarryingClub(A_PRINCIPAL_THAT_OWNS_NO_SEAT, SEAT_CLUB);

        seats.seedTheOnlySeat();

        assertThat(binding.refusesPrincipalCarryingClub(A_PRINCIPAL_THAT_OWNS_NO_SEAT, SEAT_CLUB))
                .as("AC-7 — a pool read that found no seat must not admit a foreign principal "
                        + "into a seat club for the life of the JVM")
                .isTrue();
        assertThat(binding.refusesPrincipalCarryingClub(SEAT_USERNAME, SEAT_CLUB))
                .as("AC-7 — the seat's own principal keeps its own seat club")
                .isFalse();
    }

    @Test
    void a_pool_that_holds_no_seat_refuses_nobody_because_no_club_is_a_seat_club_yet() {
        assertThat(binding.refusesPrincipalCarryingClub(A_PRINCIPAL_THAT_OWNS_NO_SEAT, A_REAL_CLUB))
                .as("an empty pool must not lock every principal out of every real club")
                .isFalse();
    }

    @Test
    void a_pool_read_that_found_a_seat_serves_every_later_request_without_a_second_read() {
        seats.seedTheOnlySeat();

        binding.refusesPrincipalCarryingClub(SEAT_USERNAME, SEAT_CLUB);
        binding.refusesPrincipalCarryingClub(A_PRINCIPAL_THAT_OWNS_NO_SEAT, SEAT_CLUB);
        binding.refusesPrincipalCarryingClub(A_PRINCIPAL_THAT_OWNS_NO_SEAT, A_REAL_CLUB);

        assertThat(seats.readCount())
                .as("the seat pool is Flyway-owned, so a read that found a seat is read once")
                .isEqualTo(1);
    }

    private static final class DemoSeatPoolThatFlywaySeedsAfterTheFirstRead
            implements DemoSeatRepository {

        private final List<DemoSeat> seated = new ArrayList<>();
        private int readCount;

        @Override
        public List<DemoSeat> findAllInSeatNumberOrder() {
            readCount++;
            return List.copyOf(seated);
        }

        @Override
        public DemoSeat save(DemoSeat seat) {
            seated.add(seat);
            return seat;
        }

        void seedTheOnlySeat() {
            save(seatRow(1, SEAT_CLUB, SEAT_USERNAME));
        }

        int readCount() {
            return readCount;
        }
    }

    private static DemoSeat seatRow(int seatNumber, UUID clubId, String keycloakUsername) {
        try {
            Constructor<DemoSeat> flywayWritesThePoolRowSoTheAggregateHasNoPublicConstructor =
                    DemoSeat.class.getDeclaredConstructor();
            flywayWritesThePoolRowSoTheAggregateHasNoPublicConstructor.setAccessible(true);
            DemoSeat seat =
                    flywayWritesThePoolRowSoTheAggregateHasNoPublicConstructor.newInstance();
            setLoadedField(seat, "id", UUID.randomUUID());
            setLoadedField(seat, "seatNumber", seatNumber);
            setLoadedField(seat, "clubId", clubId);
            setLoadedField(seat, "keycloakUsername", keycloakUsername);
            return seat;
        } catch (ReflectiveOperationException noSuchPoolRow) {
            throw new LinkageError(noSuchPoolRow.getMessage(), noSuchPoolRow);
        }
    }

    private static void setLoadedField(DemoSeat seat, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = DemoSeat.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(seat, value);
    }
}
