package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlightDomainTest {

    private static final UUID AIRCRAFT_A = UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1");
    private static final UUID AIRCRAFT_B = UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a2");
    private static final UUID PROCESS_STATE_NEW =
            UUID.fromString("019e2e15-2c00-7a98-8000-000000003a98");
    private static final UUID PILOT = UUID.fromString("019e30c3-2c00-7001-8000-00000000aaaa");
    private static final UUID CREW_TYPE_PIC = UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");

    @Test
    void createGlider_carries_discriminator() {
        Flight f = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        assertThat(f.getFlightAircraftType()).isEqualTo(FlightAircraftType.GLIDER);
        assertThat(f.getAircraftId()).isEqualTo(AIRCRAFT_A);
        assertThat(f.getProcessStateId()).isEqualTo(PROCESS_STATE_NEW);
        assertThat(f.airState()).isEqualTo(FlightAirState.NEW);
    }

    @Test
    void createTow_carries_discriminator() {
        Flight f = Flight.createTow(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        assertThat(f.getFlightAircraftType()).isEqualTo(FlightAircraftType.TOW);
    }

    @Test
    void createMotor_carries_discriminator() {
        Flight f = Flight.createMotor(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        assertThat(f.getFlightAircraftType()).isEqualTo(FlightAircraftType.MOTOR);
    }

    @Test
    void linkTow_requires_glider_caller() {
        Flight tow = Flight.createTow(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        Flight motor = Flight.createMotor(AIRCRAFT_B, PROCESS_STATE_NEW, ops());
        assertThatThrownBy(() -> motor.linkTow(tow))
                .isInstanceOf(InvalidTowLinkException.class)
                .hasMessageContaining("Only GLIDER flights may link a tow");
    }

    @Test
    void linkTow_requires_tow_target() {
        Flight glider = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        Flight motor = Flight.createMotor(AIRCRAFT_B, PROCESS_STATE_NEW, ops());
        assertThatThrownBy(() -> glider.linkTow(motor))
                .isInstanceOf(InvalidTowLinkException.class)
                .hasMessageContaining("Tow target must be a TOW flight");
    }

    @Test
    void linkTow_rejects_self_link() throws Exception {
        Flight glider = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        UUID id = UUID.fromString("019e30c3-2c00-7001-8000-00000000bbbb");
        setField(glider, "id", id);
        Flight twin = Flight.createTow(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        setField(twin, "id", id);
        assertThatThrownBy(() -> glider.linkTow(twin))
                .isInstanceOf(InvalidTowLinkException.class)
                .hasMessageContaining("may not link itself");
    }

    @Test
    void linkTow_rejects_cross_tenant_pairing() throws Exception {
        Flight glider = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        Flight tow = Flight.createTow(AIRCRAFT_B, PROCESS_STATE_NEW, ops());
        setField(glider, "operatingClubId",
                UUID.fromString("019e30c3-2c00-7001-8000-0000000000c1"));
        setField(tow, "operatingClubId",
                UUID.fromString("019e30c3-2c00-7001-8000-0000000000c2"));
        assertThatThrownBy(() -> glider.linkTow(tow))
                .isInstanceOf(InvalidTowLinkException.class)
                .hasMessageContaining("same operating club");
    }

    @Test
    void linkTow_happy_path_sets_towFlightId() throws Exception {
        Flight glider = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        Flight tow = Flight.createTow(AIRCRAFT_B, PROCESS_STATE_NEW, ops());
        UUID towId = UUID.fromString("019e30c3-2c00-7001-8000-0000000000dd");
        setField(tow, "id", towId);
        glider.linkTow(tow);
        assertThat(glider.getTowFlightId()).isEqualTo(towId);
    }

    @Test
    void temporalOrdering_rejects_landing_before_start() {
        Instant start = Instant.parse("2026-05-01T08:00:00Z");
        Instant landing = Instant.parse("2026-05-01T07:00:00Z");
        assertThatThrownBy(() -> Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW,
                opsBuilder().build(start, landing, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ldgDateTime must be >= startDateTime");
    }

    @Test
    void temporalOrdering_block_envelope_ok() {
        Instant blockStart = Instant.parse("2026-05-01T07:55:00Z");
        Instant start = Instant.parse("2026-05-01T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-01T09:00:00Z");
        Instant blockEnd = Instant.parse("2026-05-01T09:05:00Z");
        Flight f = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW,
                opsBuilder().build(start, ldg, blockStart, blockEnd));
        assertThat(f.getBlockStartDateTime()).isEqualTo(blockStart);
        assertThat(f.getBlockEndDateTime()).isEqualTo(blockEnd);
    }

    @Test
    void replaceCrew_rejects_duplicate_person_type_pair() {
        Flight f = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        CrewMemberSpec a = crewSpec(PILOT, CREW_TYPE_PIC);
        CrewMemberSpec b = crewSpec(PILOT, CREW_TYPE_PIC);
        assertThatThrownBy(() -> f.replaceCrew(List.of(a, b)))
                .isInstanceOf(DuplicateCrewMemberException.class);
    }

    @Test
    void replaceCrew_replaces_wholesale() {
        Flight f = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        f.replaceCrew(List.of(crewSpec(PILOT, CREW_TYPE_PIC)));
        UUID other = UUID.fromString("019e30c3-2c00-7001-8000-00000000bb02");
        f.replaceCrew(List.of(crewSpec(other, CREW_TYPE_PIC)));
        assertThat(f.getCrew()).hasSize(1);
        assertThat(f.getCrew().get(0).getPersonId()).isEqualTo(other);
    }

    @Test
    void softDelete_cascades_to_crew() {
        Flight f = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW, ops());
        f.replaceCrew(List.of(crewSpec(PILOT, CREW_TYPE_PIC)));
        Instant t = Instant.parse("2026-05-01T10:00:00Z");
        f.softDelete(t);
        assertThat(f.isDeleted()).isTrue();
        assertThat(f.getCrew()).allSatisfy(c -> assertThat(c.isDeleted()).isTrue());
    }

    @Test
    void runway_uppercases_and_rejects_invalid_format() {
        Flight f = Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW,
                opsBuilder().withRunways("06l", "28r"));
        assertThat(f.getStartRunway()).isEqualTo("06L");
        assertThat(f.getLdgRunway()).isEqualTo("28R");
        assertThatThrownBy(() -> Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW,
                opsBuilder().withRunways("XYZ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startRunway");
    }

    @Test
    void nrOfLdgsOnStart_must_be_le_nrOfLdgs() {
        assertThatThrownBy(() -> Flight.createGlider(AIRCRAFT_A, PROCESS_STATE_NEW,
                opsBuilder().withLdgs((short) 1, (short) 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nrOfLdgsOnStartLocation must be <= nrOfLdgs");
    }

    private static CrewMemberSpec crewSpec(UUID personId, UUID crewTypeId) {
        return new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null);
    }

    private static FlightOperationalData ops() {
        return opsBuilder().build();
    }

    private static OpsBuilder opsBuilder() {
        return new OpsBuilder();
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class OpsBuilder {
        private FlightOperationalData build() {
            return build(null, null, null, null);
        }

        private FlightOperationalData build(Instant start, Instant ldg,
                                            Instant blockStart, Instant blockEnd) {
            return new FlightOperationalData(
                    null,
                    start,
                    ldg,
                    blockStart,
                    blockEnd,
                    null, null,
                    null, null,
                    null, null,
                    null, null,
                    null, null,
                    false, false,
                    null, null,
                    null, null,
                    null,
                    null,
                    null, null,
                    false);
        }

        private FlightOperationalData withRunways(String startRunway, String ldgRunway) {
            return new FlightOperationalData(
                    LocalDate.of(2026, 5, 1),
                    null, null, null, null,
                    null, null,
                    startRunway, ldgRunway,
                    null, null,
                    null, null,
                    null, null,
                    false, false,
                    null, null,
                    null, null,
                    null,
                    null,
                    null, null,
                    false);
        }

        private FlightOperationalData withLdgs(Short nrOfLdgs, Short nrOfLdgsOnStartLocation) {
            return new FlightOperationalData(
                    LocalDate.of(2026, 5, 1),
                    null, null, null, null,
                    null, null,
                    null, null,
                    null, null,
                    null, null,
                    nrOfLdgs, nrOfLdgsOnStartLocation,
                    false, false,
                    null, null,
                    null, null,
                    null,
                    null,
                    null, null,
                    false);
        }
    }
}
