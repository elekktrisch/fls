package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.locations.domain.IcaoCodeAlreadyExistsException;
import ch.alpenflight.locations.domain.LocationNotFoundException;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.WithTenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Per-tenant Locations: same ICAO can exist in multiple clubs (once per club);
 * a Location written under tenant A is invisible to tenant B; the {@code
 * @TenantId} discriminator filter rides every read; the per-club partial
 * UNIQUE rejects same-club duplicates.
 *
 * <p>Mirrors {@code MemberStateTenantIsolationIT} for the seed/cleanup pattern.
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
        cleanupPreviousRun();
        seedClub(CLUB_A, "alpha");
        seedClub(CLUB_B, "bravo");
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void list_under_A_returns_only_A_rows() {
        LocationDetail aRow = locations.createLocation(payload("Field A " + suffix(), "AA01"));
        TenantTestContext.runAs(CLUB_B, () -> locations.createLocation(payload("Field B " + suffix(), "AA01")));

        List<String> aSeen = locations.listLocations().stream()
                .map(li -> li.id().toString())
                .toList();

        assertThat(aSeen).contains(aRow.id().toString());
        TenantTestContext.runAs(CLUB_B, () -> {
            List<String> bSeen = locations.listLocations().stream()
                    .map(li -> li.id().toString())
                    .toList();
            assertThat(bSeen).doesNotContain(aRow.id().toString());
        });
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void same_icao_in_two_different_clubs_succeeds() {
        String icao = "AB22";
        LocationDetail a = locations.createLocation(payload("LSZH A", icao));
        TenantTestContext.runAs(CLUB_B, () -> {
            LocationDetail b = locations.createLocation(payload("LSZH B", icao));
            assertThat(b.id()).isNotEqualTo(a.id());
            assertThat(b.icaoCode()).isEqualTo(icao);
        });
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void same_icao_within_one_club_fails_with_409_signal() {
        String icao = "AC33";
        locations.createLocation(payload("First", icao));
        assertThatThrownBy(() -> locations.createLocation(payload("Second", icao)))
                .isInstanceOf(IcaoCodeAlreadyExistsException.class);
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void location_from_other_club_is_invisible_via_get_by_id() {
        java.util.concurrent.atomic.AtomicReference<LocationDetail> bRowRef = new java.util.concurrent.atomic.AtomicReference<>();
        TenantTestContext.runAs(CLUB_B, () ->
                bRowRef.set(locations.createLocation(payload("B-only", "AD44"))));
        LocationId externalId = bRowRef.get().id();
        assertThatThrownBy(() -> locations.getLocation(externalId))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void no_tenant_context_yields_empty_list() {
        // No @WithTenant on this method: resolver returns NO_TENANT (nil UUID).
        // Hibernate appends WHERE club_id = nil → zero rows.
        TenantTestContext.runAs(CLUB_A, () -> locations.createLocation(payload("Hidden A", "AE55")));
        assertThat(locations.listLocations()).isEmpty();
    }

    @Test
    void no_tenant_context_inserts_fail_at_fk_constraint() {
        assertThatThrownBy(() -> locations.createLocation(payload("would-poison", "AF66")))
                .isInstanceOfAny(DataIntegrityViolationException.class);
    }

    @Test
    @WithTenant(CLUB_A_LITERAL)
    void write_persists_the_correct_club_id_to_db() {
        LocationDetail saved = locations.createLocation(payload("Persisted", "AG77"));
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM location WHERE id = ?::uuid AND club_id = ?::uuid",
                Integer.class, saved.id().value().toString(), CLUB_A.toString());
        assertThat(matches).isEqualTo(1);
    }

    private LocationCreateRequest payload(String name, String icaoCode) {
        UUID countryId = UUID.fromString(LocationsTestFixtures.SEED_COUNTRY_ID);
        UUID typeId = UUID.fromString(LocationsTestFixtures.SEED_LOCATION_TYPE_GRASS_RUNWAY);
        return new LocationCreateRequest(
                name,
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

    private void cleanupPreviousRun() {
        jdbc.update("DELETE FROM inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM location WHERE club_id IN (?::uuid, ?::uuid))",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM location WHERE club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM club WHERE id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
    }

    private void seedClub(UUID id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                """,
                id.toString(),
                TEST_NAME_PREFIX + slug,
                TEST_KEY_PREFIX + slug.charAt(0),
                countryId.toString(),
                clubStateId.toString(),
                TEST_NAME_PREFIX + slug);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
