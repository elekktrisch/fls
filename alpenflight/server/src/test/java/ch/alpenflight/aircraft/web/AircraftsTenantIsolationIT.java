package ch.alpenflight.aircraft.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.aircraft.domain.DuplicateImmatriculationException;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AircraftsTenantIsolationIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_ATI_";
    private static final String TEST_KEY_PREFIX = "IT_AC";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AircraftsService aircrafts;
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
    void list_returns_aircraft_from_every_club() {
        TenantTestContext.runAs(clubA, () -> {
            AircraftDetail aRow = aircrafts.registerAircraft(payload(uniqueImmat()));
            AircraftDetail bRow = TenantTestContext.runAs(clubB,
                    () -> aircrafts.registerAircraft(payload(uniqueImmat())));

            assertThat(aircrafts.listAircraft(null))
                    .extracting(li -> li.id().toString())
                    .contains(aRow.id().toString(), bRow.id().toString());
        });
    }

    @Test
    void register_persists_managing_club_id_from_resolver() {
        TenantTestContext.runAs(clubA, () -> {
            AircraftDetail row = aircrafts.registerAircraft(payload(uniqueImmat()));
            Integer matches = jdbc.queryForObject(
                    "SELECT count(*) FROM t_aircraft WHERE id = ?::uuid AND managing_club_id = ?::uuid",
                    Integer.class, row.id().value().toString(), clubA.toString());
            assertThat(matches).isEqualTo(1);
        });
    }

    @Test
    void immatriculation_uniqueness_is_global_across_tenants() {
        TenantTestContext.runAs(clubA, () -> {
            String shared = uniqueImmat();
            aircrafts.registerAircraft(payload(shared));
            TenantTestContext.runAs(clubB, () ->
                    assertThatThrownBy(() -> aircrafts.registerAircraft(payload(shared)))
                            .isInstanceOf(DuplicateImmatriculationException.class));
        });
    }

    @Test
    void register_without_tenant_context_throws_illegalState() {
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
