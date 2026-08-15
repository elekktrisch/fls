package ch.alpenflight.flighttypes.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeCreateRequest;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeDetail;
import ch.alpenflight.flighttypes.application.FlightTypesService;
import ch.alpenflight.flighttypes.domain.FlightTypeNotFoundException;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class FlightTypesTenantIsolationIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_FTI_";
    private static final String CLASS_UNIQUE_CLUB_KEY_PREFIX = "IT_FT";

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FlightTypesService flightTypes;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates,
                        TEST_NAME_PREFIX, CLASS_UNIQUE_CLUB_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void tenant_filter_isolates_reads_and_persists_operating_club_id() {
        TenantTestContext.runAs(clubA, () -> {
            FlightTypeDetail aRow = flightTypes.registerFlightType(payload(uniqueName()));
            AtomicReference<FlightTypeDetail> bRowRef = new AtomicReference<>();
            TenantTestContext.runAs(clubB, () ->
                    bRowRef.set(flightTypes.registerFlightType(payload(uniqueName()))));

            assertThat(flightTypes.listFlightTypes())
                    .extracting(li -> li.id().toString())
                    .contains(aRow.id().toString())
                    .doesNotContain(bRowRef.get().id().toString());

            FlightTypeId bExternal = bRowRef.get().id();
            assertThatThrownBy(() -> flightTypes.getFlightType(bExternal))
                    .isInstanceOf(FlightTypeNotFoundException.class);

            Integer matches = jdbc.queryForObject(
                    "SELECT count(*) FROM t_flight_type WHERE id = ?::uuid "
                            + "AND operating_club_id = ?::uuid",
                    Integer.class, aRow.id().value().toString(), clubA.toString());
            assertThat(matches).isEqualTo(1);
        });
    }

    @Test
    void same_name_under_two_clubs_does_not_collide() {
        String shared = uniqueName();
        TenantTestContext.runAs(clubA, () -> flightTypes.registerFlightType(payload(shared)));
        TenantTestContext.runAs(clubB, () -> flightTypes.registerFlightType(payload(shared)));

        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_type WHERE flight_type_name = ?",
                Integer.class, shared);
        assertThat(matches)
                .as("name uniqueness is per-tenant (V11 partial UNIQUE) — a global UNIQUE "
                        + "would have rejected the second club's row")
                .isEqualTo(2);
    }

    @Test
    void no_tenant_context_yields_empty_reads() {
        TenantTestContext.runAs(clubA, () ->
                flightTypes.registerFlightType(payload(uniqueName())));
        assertThat(flightTypes.listFlightTypes()).isEmpty();
    }

    @Test
    void no_tenant_context_writes_fail_at_fk_constraint() {
        assertThatThrownBy(() -> flightTypes.registerFlightType(payload(uniqueName())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static FlightTypeCreateRequest payload(String name) {
        return new FlightTypeCreateRequest(
                name, null,
                false, false, false, false, false,
                true, false, false,
                false, false, false,
                null);
    }

    private static String uniqueName() {
        return TEST_NAME_PREFIX + NAME_COUNTER.incrementAndGet();
    }
}
