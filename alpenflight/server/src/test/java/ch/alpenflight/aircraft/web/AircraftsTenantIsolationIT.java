package ch.alpenflight.aircraft.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.aircraft.domain.AircraftNotFoundException;
import ch.alpenflight.aircraft.domain.DuplicateImmatriculationException;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.server.testsupport.WithTenant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-layer tenancy properties for the Aircraft aggregate (S-159: Aircraft
 * becomes tenant-scoped via {@code managing_club_id}). Counterpart to
 * {@code LocationsTenantIsolationIT}. The Hibernate {@code @TenantId}
 * discriminator filters reads/writes by the resolved tenant; aviation-
 * regulator immatriculation stays GLOBAL unique unlike Locations' per-club
 * ICAO uniqueness.
 *
 * <p>Aggregate-level rules (immatriculation regex, monotonic counters,
 * state-history "exactly one open") live in {@code AircraftDomainTest};
 * HTTP routing + authz live in {@code AircraftsControllerIT} /
 * {@code AircraftsAuthorizationIT}.
 */
class AircraftsTenantIsolationIT extends PostgresIntegrationTest {

    private static final String CLUB_A_LITERAL = "019e30c3-2c00-7001-8000-0000000000d1";
    private static final String CLUB_B_LITERAL = "019e30c3-2c00-7001-8000-0000000000d2";
    private static final UUID CLUB_A = UUID.fromString(CLUB_A_LITERAL);
    private static final UUID CLUB_B = UUID.fromString(CLUB_B_LITERAL);

    private static final String TEST_NAME_PREFIX = "IT_ATI_";
    private static final String TEST_KEY_PREFIX = "IT_A_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AircraftsService aircrafts;

    @BeforeEach
    void seedTwoClubs() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, TEST_NAME_PREFIX, TEST_KEY_PREFIX).seed();
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void tenant_filter_isolates_reads_and_persists_managing_club_id() {
        AircraftDetail aRow = aircrafts.registerAircraft(payload(uniqueImmat()));
        AtomicReference<AircraftDetail> bRowRef = new AtomicReference<>();
        TenantTestContext.runAs(CLUB_B, () ->
                bRowRef.set(aircrafts.registerAircraft(payload(uniqueImmat()))));

        // A's list contains A's row; getById of B's row throws (invisible to A).
        assertThat(aircrafts.listAircraft(null))
                .extracting(li -> li.id().toString())
                .contains(aRow.id().toString())
                .doesNotContain(bRowRef.get().id().toString());
        AircraftId bExternal = bRowRef.get().id();
        assertThatThrownBy(() -> aircrafts.getAircraft(bExternal))
                .isInstanceOf(AircraftNotFoundException.class);

        // The persisted row carries A's managing_club_id (not B's, not nil).
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM aircraft WHERE id = ?::uuid AND managing_club_id = ?::uuid",
                Integer.class, aRow.id().value().toString(), CLUB_A.toString());
        assertThat(matches).isEqualTo(1);
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void immatriculation_uniqueness_is_global_across_tenants() {
        // Unlike Locations (per-club partial UNIQUE on ICAO), aircraft
        // immatriculation is regulator-global. Same immatriculation under
        // CLUB_B collides — proves we did NOT regress to per-tenant uniqueness
        // when adding @TenantId on managing_club_id.
        String shared = uniqueImmat();
        aircrafts.registerAircraft(payload(shared));
        TenantTestContext.runAs(CLUB_B, () ->
                assertThatThrownBy(() -> aircrafts.registerAircraft(payload(shared)))
                        .isInstanceOf(DuplicateImmatriculationException.class));
    }

    @Test
    void no_tenant_context_yields_empty_reads() {
        TenantTestContext.runAs(CLUB_A, () ->
                aircrafts.registerAircraft(payload(uniqueImmat())));
        // Resolver returns NO_TENANT outside any @WithTenant / runAs block.
        assertThat(aircrafts.listAircraft(null)).isEmpty();
    }

    @Test
    void no_tenant_context_writes_fail_at_fk_constraint() {
        // No real row carries the nil UUID, so fk_aircraft_managing_club_id
        // rejects the write — the fail-closed half of the @TenantId contract.
        assertThatThrownBy(() -> aircrafts.registerAircraft(payload(uniqueImmat())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- helpers -----

    private static AircraftCreateRequest payload(String immatriculation) {
        // managing_club_id is derived from the tenant resolver; not in the DTO.
        return new AircraftCreateRequest(
                AircraftTypeId.of(UUID.fromString(AircraftsTestFixtures.SEED_AIRCRAFT_TYPE_GLIDER)),
                immatriculation,
                "Schleicher", "ASK-21",
                null, null, null, null,
                null, null, null, 2,
                null, null, null, null,
                true, true, true, false,
                "IT_ATI fixture",
                null);
    }

    private static String uniqueImmat() {
        return AircraftsTestFixtures.uniqueImmatriculation();
    }
}
