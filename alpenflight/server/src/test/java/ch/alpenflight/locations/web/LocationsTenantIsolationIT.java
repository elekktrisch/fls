package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.domain.IcaoCodeAlreadyExistsException;
import ch.alpenflight.locations.domain.LocationNotFoundException;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class LocationsTenantIsolationIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_LTI_";
    private static final String TEST_KEY_PREFIX = "IT_L_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocationsService locations;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, TEST_NAME_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void tenant_filter_isolates_reads_and_persists_club_id() {
        TenantTestContext.runAs(clubA, () -> {
            LocationDetail aRow = locations.createLocation(payload("Field A", "AA01"));
            AtomicReference<LocationDetail> bRowRef = new AtomicReference<>();
            TenantTestContext.runAs(clubB, () ->
                    bRowRef.set(locations.createLocation(payload("Field B", "AB02"))));

            assertThat(locations.listLocations())
                    .extracting(li -> li.id().toString())
                    .contains(aRow.id().toString())
                    .doesNotContain(bRowRef.get().id().toString());
            LocationId bExternal = bRowRef.get().id();
            assertThatThrownBy(() -> locations.getLocation(bExternal))
                    .isInstanceOf(LocationNotFoundException.class);

            Integer matches = jdbc.queryForObject(
                    "SELECT count(*) FROM t_location WHERE id = ?::uuid AND club_id = ?::uuid",
                    Integer.class, aRow.id().value().toString(), clubA.toString());
            assertThat(matches).isEqualTo(1);
        });
    }

    @Test
    void same_icao_coexists_across_clubs_but_collides_within_one_club() {
        TenantTestContext.runAs(clubA, () -> {
            String icao = "AC33";
            locations.createLocation(payload("Same ICAO A", icao));
            TenantTestContext.runAs(clubB, () -> {
                LocationDetail b = locations.createLocation(payload("Same ICAO B", icao));
                assertThat(b.icaoCode()).isEqualTo(icao);
            });
            assertThatThrownBy(() -> locations.createLocation(payload("Dup in A", icao)))
                    .isInstanceOf(IcaoCodeAlreadyExistsException.class);
        });
    }

    @Test
    void no_tenant_context_yields_empty_reads() {
        TenantTestContext.runAs(clubA, () ->
                locations.createLocation(payload("Hidden A", "AE55")));
        assertThat(locations.listLocations()).isEmpty();
    }

    @Test
    void no_tenant_context_writes_fail_at_fk_constraint() {
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
