package ch.alpenflight.clubs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DiscoveryFlightDayTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-02T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void schedules_a_future_day() {
        DiscoveryFlightDay day = DiscoveryFlightDay.schedule(LocalDate.of(2099, 6, 15), TODAY);

        assertThat(day.getEventDate()).isEqualTo(LocalDate.of(2099, 6, 15));
        assertThat(day.isBookableOn(TODAY)).isTrue();
    }

    @Test
    void today_itself_is_still_bookable() {
        DiscoveryFlightDay day = DiscoveryFlightDay.schedule(TODAY, TODAY);

        assertThat(day.isBookableOn(TODAY)).isTrue();
    }

    @Test
    void rejects_scheduling_into_the_past() {
        assertThatThrownBy(() -> DiscoveryFlightDay.schedule(TODAY.minusDays(1), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("past");
    }

    @Test
    void a_day_stops_being_bookable_once_it_has_passed() {
        DiscoveryFlightDay day = DiscoveryFlightDay.schedule(TODAY.plusDays(3), TODAY);

        assertThat(day.isBookableOn(TODAY.plusDays(4))).isFalse();
    }

    @Test
    void a_withdrawn_day_is_not_bookable_and_cannot_be_rescheduled() {
        DiscoveryFlightDay day = DiscoveryFlightDay.schedule(TODAY.plusDays(3), TODAY);
        day.softDelete(null, CLOCK);

        assertThat(day.isDeleted()).isTrue();
        assertThat(day.isBookableOn(TODAY)).isFalse();
        assertThatThrownBy(() -> day.reschedule(TODAY.plusDays(9), TODAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reschedule_moves_the_day_but_not_backwards() {
        DiscoveryFlightDay day = DiscoveryFlightDay.schedule(TODAY.plusDays(3), TODAY);

        day.reschedule(TODAY.plusDays(10), TODAY);
        assertThat(day.getEventDate()).isEqualTo(TODAY.plusDays(10));

        assertThatThrownBy(() -> day.reschedule(TODAY.minusDays(1), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(day.getEventDate()).isEqualTo(TODAY.plusDays(10));
    }
}
