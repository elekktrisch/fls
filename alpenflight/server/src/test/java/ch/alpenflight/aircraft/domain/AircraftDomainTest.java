package ch.alpenflight.aircraft.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AircraftDomainTest {

    private static final UUID GLIDER_TYPE = UUID.fromString("019e2e15-2c00-7af9-8000-000000002af9");
    private static final UUID STATE_OK = UUID.fromString("019e2e15-2c00-7ee0-8000-000000002ee0");
    private static final UUID STATE_MAINT = UUID.fromString("019e2e15-2c00-7ee4-8000-000000002ee4");
    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    @Test
    void register_minimal_setsImmatriculationToUpperCase() {
        Aircraft a = registered("hb-123");
        assertThat(a.getImmatriculation()).isEqualTo("HB-123");
        assertThat(a.getOwnerClubId()).isEqualTo(CLUB_A);
        assertThat(a.isDeleted()).isFalse();
    }

    @Test
    void register_blankImmatriculation_rejects() {
        assertThatThrownBy(() -> registered("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void register_immatriculationWithIllegalCharacters_rejects() {
        assertThatThrownBy(() -> registered("HB 123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immatriculation must match");
    }

    @Test
    void register_immatriculationTooShort_rejects() {
        assertThatThrownBy(() -> registered("A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeState_opensFirstOpenPeriod() {
        Aircraft a = registered("HB-100");
        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        a.changeState(STATE_OK, t0, null, null);
        assertThat(a.getStateHistory()).hasSize(1);
        AircraftStateHistoryEntry open = a.getCurrentStateEntry().orElseThrow();
        assertThat(open.getValidFrom()).isEqualTo(t0);
        assertThat(open.getValidTo()).isNull();
        assertThat(open.getAircraftStateId()).isEqualTo(STATE_OK);
    }

    @Test
    void changeState_closesPreviousOpenPeriod_andOpensNew() {
        Aircraft a = registered("HB-101");
        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        Instant t1 = Instant.parse("2026-02-01T08:00:00Z");
        a.changeState(STATE_OK, t0, null, null);
        a.changeState(STATE_MAINT, t1, null, "scheduled inspection");

        assertThat(a.getStateHistory()).hasSize(2);
        long openCount = a.getStateHistory().stream()
                .filter(AircraftStateHistoryEntry::isOpen)
                .count();
        assertThat(openCount).as("exactly one open state period").isEqualTo(1);
        AircraftStateHistoryEntry current = a.getCurrentStateEntry().orElseThrow();
        assertThat(current.getAircraftStateId()).isEqualTo(STATE_MAINT);
        assertThat(current.getValidFrom()).isEqualTo(t1);

        AircraftStateHistoryEntry closed = a.getStateHistory().stream()
                .filter(e -> !e.isOpen())
                .findFirst()
                .orElseThrow();
        assertThat(closed.getValidTo()).isEqualTo(t1);
    }

    @Test
    void changeState_validFromNotAfterPrevious_rejects() {
        Aircraft a = registered("HB-102");
        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        a.changeState(STATE_OK, t0, null, null);
        assertThatThrownBy(() -> a.changeState(STATE_MAINT, t0, null, null))
                .isInstanceOf(AircraftStateConflictException.class)
                .hasMessageContaining("strictly after");
    }

    @Test
    void recordCounter_first_accepts() {
        Aircraft a = registered("HB-200");
        Instant t = Instant.parse("2026-01-01T10:00:00Z");
        a.recordCounter(t, 0, 0, 0, 0L, 0L, null, null);
        assertThat(a.getOperatingCounters()).hasSize(1);
        assertThat(a.getLatestCounter()).isPresent();
    }

    @Test
    void recordCounter_monotonicTotals_acceptsIncrease() {
        Aircraft a = registered("HB-201");
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t1 = Instant.parse("2026-02-01T10:00:00Z");
        a.recordCounter(t0, 10, 0, 0, 3600L, 3600L, null, null);
        a.recordCounter(t1, 15, 0, 0, 7200L, 7200L, null, null);
        assertThat(a.getLatestCounter().orElseThrow().getFlightOperatingCounterInSeconds())
                .isEqualTo(7200L);
    }

    @Test
    void recordCounter_decreasingFlightSeconds_rejects() {
        Aircraft a = registered("HB-202");
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t1 = Instant.parse("2026-02-01T10:00:00Z");
        a.recordCounter(t0, 0, 0, 0, 7200L, 7200L, null, null);
        assertThatThrownBy(() ->
                a.recordCounter(t1, 0, 0, 0, 3600L, 7200L, null, null))
                .isInstanceOf(CounterMonotonicityException.class);
    }

    @Test
    void recordCounter_atDateTimeNotAfterPrevious_rejects() {
        Aircraft a = registered("HB-203");
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        a.recordCounter(t0, 0, 0, 0, 0L, 0L, null, null);
        assertThatThrownBy(() ->
                a.recordCounter(t0, 0, 0, 0, 0L, 0L, null, null))
                .isInstanceOf(CounterMonotonicityException.class);
    }

    @Test
    void recordCounter_negativeTotals_rejects() {
        Aircraft a = registered("HB-204");
        assertThatThrownBy(() ->
                a.recordCounter(Instant.parse("2026-01-01T10:00:00Z"),
                        -1, 0, 0, 0L, 0L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    void updateMasterdata_lowercaseCompetitionSign_isUppercased() {
        Aircraft a = registered("HB-300");
        a.updateMasterdata(null, null, "az1", null, null, null, null, null,
                null, null, null, null, null, null, null,
                false, false, false, false, null, null);
        assertThat(a.getCompetitionSign()).isEqualTo("AZ1");
    }

    @Test
    void updateMasterdata_competitionSignTooLong_rejects() {
        Aircraft a = registered("HB-301");
        assertThatThrownBy(() ->
                a.updateMasterdata(null, null, "ABCDEF", null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        false, false, false, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("competitionSign");
    }

    @Test
    void updateMasterdata_validFlarmId_isUppercased() {
        Aircraft a = registered("HB-302");
        a.updateMasterdata(null, null, null, "deadbe", null, null, null, null,
                null, null, null, null, null, null, null,
                false, false, false, false, null, null);
        assertThat(a.getFlarmId()).isEqualTo("DEADBE");
    }

    @Test
    void updateMasterdata_invalidFlarmId_rejects() {
        Aircraft a = registered("HB-303");
        assertThatThrownBy(() ->
                a.updateMasterdata(null, null, null, "notHex", null, null, null, null,
                        null, null, null, null, null, null, null,
                        false, false, false, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flarmId");
    }

    @Test
    void updateMasterdata_httpSpotLink_rejects() {
        Aircraft a = registered("HB-304");
        assertThatThrownBy(() ->
                a.updateMasterdata(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        "http://example.com/spot",
                        false, false, false, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void updateMasterdata_negativeMtom_rejects() {
        Aircraft a = registered("HB-305");
        assertThatThrownBy(() ->
                a.updateMasterdata(null, null, null, null, null, null, null, null,
                        -1, null, null, null, null, null, null,
                        false, false, false, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mtom");
    }

    @Test
    void transferOwnership_setsBothOwnerFields() {
        Aircraft a = registered("HB-400");
        UUID clubB = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");
        UUID personC = UUID.fromString("019e30c3-2c00-7001-8000-000000000999");
        a.transferOwnership(clubB, personC);
        assertThat(a.getOwnerClubId()).isEqualTo(clubB);
        assertThat(a.getAircraftOwnerPersonId()).isEqualTo(personC);
    }

    @Test
    void softDelete_isIdempotent_aSecondCallByAnotherActorChangesNothing() {
        Aircraft a = registered("HB-500");
        UUID actor = UUID.randomUUID();
        a.softDelete(actor, java.time.Clock.systemUTC());
        assertThat(a.isDeleted()).isTrue();

        a.softDelete(UUID.randomUUID(), java.time.Clock.systemUTC());
        assertThat(a.isDeleted())
                .as("re-deleting is a no-op: the aggregate stays deleted, the first actor wins")
                .isTrue();
    }

    @Test
    void immatriculation_forMatching_stripsDashesAndUppercases() {
        assertThat(Immatriculation.forMatching("hb-123")).isEqualTo("HB123");
        assertThat(Immatriculation.forMatching("HB-1-2-3")).isEqualTo("HB123");
    }

    private static Aircraft registered(String immatriculation) {
        return Aircraft.register(CLUB_A, CLUB_A, GLIDER_TYPE, immatriculation,
                null, null, null, null, null, LocalDate.of(2020, 1, 1),
                null, null, null, null, null, null, null, null, null,
                false, false, false, false, null, null);
    }
}
