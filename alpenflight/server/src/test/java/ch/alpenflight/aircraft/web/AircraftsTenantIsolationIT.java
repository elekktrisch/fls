package ch.alpenflight.aircraft.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.aircraft.domain.DuplicateImmatriculationException;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.server.testsupport.WithTenant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-layer cross-tenant behaviour for the Aircraft aggregate (S-058
 * reverts S-159's tenant-scoping). Reads are unscoped — any tenant context
 * lists rows from every club so the Flight aircraft picker can surface
 * other clubs' aircraft for the charter case. Writes are restricted to the
 * managing-club at the HTTP layer (see {@code AircraftsAuthorizationIT})
 * via the {@code AircraftAccess} SpEL bean.
 *
 * <p>Service-layer registration still demands a tenant context (it sources
 * the managing_club_id from the resolver). The no-tenant fallback path
 * exists for system-admin / cutover via {@code Tenants.runAs}.
 */
class AircraftsTenantIsolationIT extends PostgresIntegrationTest {

    private static final String CLUB_A_LITERAL = "019e30c3-2c00-7001-8000-0000000000d1";
    private static final String CLUB_B_LITERAL = "019e30c3-2c00-7001-8000-0000000000d2";
    private static final UUID CLUB_A = UUID.fromString(CLUB_A_LITERAL);
    private static final UUID CLUB_B = UUID.fromString(CLUB_B_LITERAL);

    private static final String TEST_NAME_PREFIX = "IT_ATI_";
    private static final String TEST_KEY_PREFIX = "IT_AC";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AircraftsService aircrafts;

    @BeforeEach
    void seedTwoClubs() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, TEST_NAME_PREFIX, TEST_KEY_PREFIX).seed();
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void list_returns_aircraft_from_every_club() {
        AircraftDetail aRow = aircrafts.registerAircraft(payload(uniqueImmat()));
        AircraftDetail bRow = TenantTestContext.runAs(CLUB_B,
                () -> aircrafts.registerAircraft(payload(uniqueImmat())));

        // Cross-tenant catalog: Club A's list includes Club B's row.
        assertThat(aircrafts.listAircraft(null))
                .extracting(li -> li.id().toString())
                .contains(aRow.id().toString(), bRow.id().toString());
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void register_persists_managing_club_id_from_resolver() {
        AircraftDetail row = aircrafts.registerAircraft(payload(uniqueImmat()));
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft WHERE id = ?::uuid AND managing_club_id = ?::uuid",
                Integer.class, row.id().value().toString(), CLUB_A.toString());
        assertThat(matches).isEqualTo(1);
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void immatriculation_uniqueness_is_global_across_tenants() {
        String shared = uniqueImmat();
        aircrafts.registerAircraft(payload(shared));
        TenantTestContext.runAs(CLUB_B, () ->
                assertThatThrownBy(() -> aircrafts.registerAircraft(payload(shared)))
                        .isInstanceOf(DuplicateImmatriculationException.class));
    }

    @Test
    void register_without_tenant_context_throws_illegalState() {
        // Service refuses to register without a resolved manager — the
        // controller's @PreAuthorize ensures this path isn't reached from
        // an authenticated HTTP request, but the service still fails closed.
        assertThatThrownBy(() -> aircrafts.registerAircraft(payload(uniqueImmat())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant context");
    }

    private static AircraftCreateRequest payload(String immatriculation) {
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
