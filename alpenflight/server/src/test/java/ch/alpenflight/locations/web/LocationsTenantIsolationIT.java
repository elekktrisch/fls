package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.domain.IcaoCodeAlreadyExistsException;
import ch.alpenflight.locations.domain.LocationNotFoundException;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.server.testsupport.WithTenant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-layer tenancy properties for the Locations aggregate:
 *
 * <ul>
 *   <li>The {@code @TenantId} discriminator filters reads (list + getById)
 *       under the current tenant.</li>
 *   <li>Per-club partial UNIQUE on {@code (club_id, icao_code)} rejects
 *       same-club ICAO collisions but accepts cross-club coexistence.</li>
 *   <li>The {@code NO_TENANT} sentinel yields empty reads and FK-rejected
 *       writes.</li>
 *   <li>Writes persist the resolved {@code club_id} to the row.</li>
 * </ul>
 *
 * <p>Per CONVENTIONS.md §"Test pyramid", these are the cross-layer
 * properties that can only be proved with a live Hibernate + Postgres
 * stack. Aggregate-level rules (ICAO regex, blank-name rejection) live in
 * {@code LocationDomainTest}; HTTP routing + authz live in
 * {@code LocationsControllerIT} / {@code LocationsAuthorizationIT}.
 */
class LocationsTenantIsolationIT extends PostgresIntegrationTest {

    private static final String CLUB_A_LITERAL = "019e30c3-2c00-7001-8000-0000000000b1";
    private static final String CLUB_B_LITERAL = "019e30c3-2c00-7001-8000-0000000000b2";
    private static final UUID CLUB_A = UUID.fromString(CLUB_A_LITERAL);
    private static final UUID CLUB_B = UUID.fromString(CLUB_B_LITERAL);

    private static final String TEST_NAME_PREFIX = "IT_LTI_";
    private static final String TEST_KEY_PREFIX = "IT_L_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocationsService locations;

    @BeforeEach
    void seedTwoClubs() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, TEST_NAME_PREFIX, TEST_KEY_PREFIX).seed();
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void tenant_filter_isolates_reads_and_persists_club_id() {
        LocationDetail aRow = locations.createLocation(payload("Field A", "AA01"));
        AtomicReference<LocationDetail> bRowRef = new AtomicReference<>();
        TenantTestContext.runAs(CLUB_B, () ->
                bRowRef.set(locations.createLocation(payload("Field B", "AB02"))));

        // A's list contains A's row; getById of B's row throws (invisible to A).
        assertThat(locations.listLocations())
                .extracting(li -> li.id().toString())
                .contains(aRow.id().toString())
                .doesNotContain(bRowRef.get().id().toString());
        LocationId bExternal = bRowRef.get().id();
        assertThatThrownBy(() -> locations.getLocation(bExternal))
                .isInstanceOf(LocationNotFoundException.class);

        // The persisted row carries A's club_id (not B's, not nil).
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM t_location WHERE id = ?::uuid AND club_id = ?::uuid",
                Integer.class, aRow.id().value().toString(), CLUB_A.toString());
        assertThat(matches).isEqualTo(1);
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void same_icao_coexists_across_clubs_but_collides_within_one_club() {
        String icao = "AC33";
        locations.createLocation(payload("Same ICAO A", icao));
        TenantTestContext.runAs(CLUB_B, () -> {
            LocationDetail b = locations.createLocation(payload("Same ICAO B", icao));
            assertThat(b.icaoCode()).isEqualTo(icao);
        });
        assertThatThrownBy(() -> locations.createLocation(payload("Dup in A", icao)))
                .isInstanceOf(IcaoCodeAlreadyExistsException.class);
    }

    @Test
    void no_tenant_context_yields_empty_reads() {
        // Resolver returns NO_TENANT (nil UUID) when no @WithTenant. Hibernate
        // appends WHERE club_id = nil-UUID → zero rows.
        TenantTestContext.runAs(CLUB_A, () ->
                locations.createLocation(payload("Hidden A", "AE55")));
        assertThat(locations.listLocations()).isEmpty();
    }

    @Test
    void no_tenant_context_writes_fail_at_fk_constraint() {
        // No real row carries the nil UUID, so the FK constraint
        // fk_location_club_id rejects the write — the fail-closed half of
        // the @TenantId contract.
        assertThatThrownBy(() -> locations.createLocation(payload("would-poison", "AF66")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private LocationCreateRequest payload(String name, String icaoCode) {
        UUID countryId = UUID.fromString(LocationsTestFixtures.SEED_COUNTRY_ID);
        UUID typeId = UUID.fromString(LocationsTestFixtures.SEED_LOCATION_TYPE_GRASS_RUNWAY);
        return new LocationCreateRequest(
                name + " " + Long.toString(System.nanoTime(), 36),
                null,
                CountryId.of(countryId),
                LocationTypeId.of(typeId),
                icaoCode,
                null, null,
                null, null,
                null, null, null,
                null,
                null,
                null,
                false, false, false,
                List.of());
    }

}
