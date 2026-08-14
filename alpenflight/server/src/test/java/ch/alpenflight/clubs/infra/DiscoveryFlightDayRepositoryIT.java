package ch.alpenflight.clubs.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Round-trip + schema proof for {@link DiscoveryFlightDay}. Covers what crosses
 * the DB: the {@code ux_discovery_flight_day_club_date} partial UNIQUE, the
 * {@code @TenantId} boundary, and the bookable-days read — driven through the
 * production {@link Tenants#runAs} window with no authenticated principal,
 * which is how the anonymous public form will reach it. The date rules
 * themselves are unit-tested (DiscoveryFlightDayTest).
 */
class DiscoveryFlightDayRepositoryIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_DFD_";
    private static final String KEY_PREFIX = "IT_DF_";

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 2);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 6, 15);
    private static final LocalDate FUTURE_DAY = LocalDate.of(2099, 6, 15);
    private static final LocalDate SECOND_FUTURE_DAY = LocalDate.of(2099, 8, 25);

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-02T09:00:00Z"), ZoneOffset.UTC);

    @Autowired private DiscoveryFlightDayRepository days;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private JdbcTemplate jdbc;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void persists_a_day_under_the_runAs_club_and_reads_it_back() {
        UUID id = publish(clubA, FUTURE_DAY, TODAY);

        var loaded = Tenants.runAs(clubA, () -> days.findActiveById(id));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getEventDate()).isEqualTo(FUTURE_DAY);
        assertThat(loaded.get().getClubId()).isEqualTo(clubA);
    }

    @Test
    void bookable_read_excludes_past_and_withdrawn_days() {
        UUID pastId = publish(clubA, PAST_DAY, PAST_DAY);
        UUID withdrawnId = publish(clubA, SECOND_FUTURE_DAY, TODAY);
        UUID bookableId = publish(clubA, FUTURE_DAY, TODAY);
        withdraw(clubA, withdrawnId);

        Tenants.runAs(clubA, () -> {
            assertThat(days.findBookableFrom(TODAY))
                    .as("only the future, still-published day is offered")
                    .extracting(DiscoveryFlightDay::getId)
                    .containsExactly(bookableId)
                    .doesNotContain(pastId, withdrawnId);
            assertThat(days.findAllActive())
                    .as("the admin list keeps the past day; only the withdrawn one is gone")
                    .extracting(DiscoveryFlightDay::getId)
                    .containsExactly(pastId, bookableId);
        });
    }

    @Test
    void a_days_list_is_tenant_scoped_to_the_runAs_club() {
        UUID idA = publish(clubA, FUTURE_DAY, TODAY);
        publish(clubB, SECOND_FUTURE_DAY, TODAY);

        Tenants.runAs(clubA, () ->
                assertThat(days.findBookableFrom(TODAY))
                        .extracting(DiscoveryFlightDay::getEventDate)
                        .containsExactly(FUTURE_DAY));
        Tenants.runAs(clubB, () -> {
            assertThat(days.findBookableFrom(TODAY))
                    .extracting(DiscoveryFlightDay::getEventDate)
                    .containsExactly(SECOND_FUTURE_DAY);
            assertThat(days.findActiveById(idA))
                    .as("club B must not reach club A's day by id")
                    .isEmpty();
        });
    }

    @Test
    void the_partial_unique_rejects_a_second_live_entry_for_the_same_date() {
        publish(clubA, FUTURE_DAY, TODAY);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .as("ux_discovery_flight_day_club_date — one live day per (club, date)")
                .isThrownBy(() -> publish(clubA, FUTURE_DAY, TODAY));
    }

    @Test
    void the_same_date_is_free_again_once_the_day_is_withdrawn() {
        UUID first = publish(clubA, FUTURE_DAY, TODAY);
        withdraw(clubA, first);

        Tenants.runAs(clubA, () -> assertThat(days.findActiveByEventDate(FUTURE_DAY))
                .as("the pre-check sees the date as free")
                .isEmpty());

        UUID second = publish(clubA, FUTURE_DAY, TODAY);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void a_second_club_may_publish_the_same_date() {
        publish(clubA, FUTURE_DAY, TODAY);

        assertThat(publish(clubB, FUTURE_DAY, TODAY)).isNotNull();
    }

    private UUID publish(UUID clubId, LocalDate eventDate, LocalDate today) {
        return Tenants.runAs(clubId, () -> Objects.requireNonNull(
                days.save(DiscoveryFlightDay.schedule(eventDate, today)).getId(),
                "save returned a null id"));
    }

    private void withdraw(UUID clubId, UUID id) {
        Tenants.runAs(clubId, () -> {
            DiscoveryFlightDay day = days.findActiveById(id).orElseThrow();
            day.softDelete(null, clock);
            days.save(day);
        });
    }
}
