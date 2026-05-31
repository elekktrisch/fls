package ch.alpenflight.flighttypes.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeCreateRequest;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeDetail;
import ch.alpenflight.flighttypes.application.FlightTypesService;
import ch.alpenflight.flighttypes.domain.FlightTypeNotFoundException;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.server.testsupport.WithTenant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-layer tenancy properties for the FlightType aggregate. The
 * Hibernate {@code @TenantId} discriminator filters reads/writes by the
 * resolved tenant; name uniqueness is per-tenant (V11 partial UNIQUE on
 * {@code (operating_club_id, flight_type_name) WHERE deleted_on IS NULL}).
 *
 * <p>HTTP-layer authz + 404-not-403 matrix lives in
 * {@link FlightTypesAuthorizationIT}; aggregate-level rules live in the
 * domain tests under {@code flighttypes.domain}.
 */
class FlightTypesTenantIsolationIT extends PostgresIntegrationTest {

    private static final String CLUB_A_LITERAL = "019e30c3-2c00-7001-8000-0000000000f1";
    private static final String CLUB_B_LITERAL = "019e30c3-2c00-7001-8000-0000000000f2";
    private static final UUID CLUB_A = UUID.fromString(CLUB_A_LITERAL);
    private static final UUID CLUB_B = UUID.fromString(CLUB_B_LITERAL);

    private static final String TEST_NAME_PREFIX = "IT_FTI_";
    // Each IT owns a unique 2-char prefix slot for ux_club_key — see
    // AircraftsTenantIsolationIT for the precedent that flagged this.
    private static final String TEST_KEY_PREFIX = "IT_FT";

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FlightTypesService flightTypes;

    @BeforeEach
    void seedTwoClubs() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, TEST_NAME_PREFIX, TEST_KEY_PREFIX).seed();
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void tenant_filter_isolates_reads_and_persists_operating_club_id() {
        FlightTypeDetail aRow = flightTypes.registerFlightType(payload(uniqueName()));
        AtomicReference<FlightTypeDetail> bRowRef = new AtomicReference<>();
        TenantTestContext.runAs(CLUB_B, () ->
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
                Integer.class, aRow.id().value().toString(), CLUB_A.toString());
        assertThat(matches).isEqualTo(1);
    }

    @Test
    void same_name_under_two_clubs_does_not_collide() {
        // Name uniqueness is per-tenant — CLUB_A and CLUB_B both create the
        // same name and both succeed. Catches the trap of accidentally
        // promoting the UNIQUE to a global scope when porting the V11 index.
        String shared = uniqueName();
        TenantTestContext.runAs(CLUB_A, () -> flightTypes.registerFlightType(payload(shared)));
        TenantTestContext.runAs(CLUB_B, () -> flightTypes.registerFlightType(payload(shared)));

        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_type WHERE flight_type_name = ?",
                Integer.class, shared);
        assertThat(matches).isEqualTo(2);
    }

    @Test
    void no_tenant_context_yields_empty_reads() {
        TenantTestContext.runAs(CLUB_A, () ->
                flightTypes.registerFlightType(payload(uniqueName())));
        assertThat(flightTypes.listFlightTypes()).isEmpty();
    }

    @Test
    void no_tenant_context_writes_fail_at_fk_constraint() {
        // No real row carries the nil UUID, so fk_flight_type_operating_club_id
        // rejects the write — the fail-closed half of the @TenantId contract.
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
