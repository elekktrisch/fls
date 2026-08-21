package ch.alpenflight.locations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationDomainTest {

    private static final String LEGACY_ICAO = "J0CX";
    private static final UUID CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID GRASS = UUID.fromString("019e2e15-2c00-72c9-8000-0000000032c9");

    @Test
    void create_trimsAndKeepsNullableShortName() {
        Location loc = Location.create(
                "  Mountain Airfield  ", null,
                CH, GRASS,
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                false, false, false);
        assertThat(loc.getLocationName()).isEqualTo("Mountain Airfield");
        assertThat(loc.getLocationShortName()).isNull();
    }

    @Test
    void rename_rejectsBlank() {
        Location loc = newLoc();
        assertThatThrownBy(() -> loc.rename("   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void setIcao_rejects_lowercase_throwsDomainException() {
        Location loc = newLoc();
        assertThatThrownBy(() -> loc.setIcaoValidatingOnlyAChangedValue("abcd"))
                .isInstanceOf(IcaoCodeInvalidException.class);
    }

    @Test
    void setIcao_rejects_wrongLength_throwsDomainException() {
        Location loc = newLoc();
        assertThatThrownBy(() -> loc.setIcaoValidatingOnlyAChangedValue("ABC"))
                .isInstanceOf(IcaoCodeInvalidException.class);
        assertThatThrownBy(() -> loc.setIcaoValidatingOnlyAChangedValue("ABCDE"))
                .isInstanceOf(IcaoCodeInvalidException.class);
    }

    @Test
    void setIcao_accepts_fourLetter_andTwoLetterTwoDigit() {
        Location loc = newLoc();
        loc.setIcaoValidatingOnlyAChangedValue("LSZH");
        assertThat(loc.getIcaoCode()).isEqualTo("LSZH");
        loc.setIcaoValidatingOnlyAChangedValue("AB12");
        assertThat(loc.getIcaoCode()).isEqualTo("AB12");
    }

    @Test
    void setIcao_acceptsNull_andBlankBecomesNull() {
        Location loc = newLoc();
        loc.setIcaoValidatingOnlyAChangedValue(null);
        assertThat(loc.getIcaoCode()).isNull();
        loc.setIcaoValidatingOnlyAChangedValue("   ");
        assertThat(loc.getIcaoCode()).isNull();
    }

    @Test
    void setIcao_keepsALegacyValueOutsideThePattern_whenTheSubmittedValueIsUnchanged() {
        Location migrated = locationCarryingTheLegacyIcaoTheMigrationWrote(LEGACY_ICAO);
        migrated.setIcaoValidatingOnlyAChangedValue(LEGACY_ICAO);
        assertThat(migrated.getIcaoCode())
                .as("the migration writes a legacy ICAO past the aggregate; an unchanged submission "
                        + "must retain it, so a migrated Location stays editable")
                .isEqualTo(LEGACY_ICAO);
    }

    @Test
    void setIcao_rejectsAChangeAwayFromALegacyValueToAnotherValueOutsideThePattern() {
        Location migrated = locationCarryingTheLegacyIcaoTheMigrationWrote(LEGACY_ICAO);
        assertThatThrownBy(() -> migrated.setIcaoValidatingOnlyAChangedValue("J0CY"))
                .isInstanceOf(IcaoCodeInvalidException.class);
        assertThat(migrated.getIcaoCode()).isEqualTo(LEGACY_ICAO);
    }

    @Test
    void setIcao_acceptsAChangeFromALegacyValueToOneInsideThePattern() {
        Location migrated = locationCarryingTheLegacyIcaoTheMigrationWrote(LEGACY_ICAO);
        migrated.setIcaoValidatingOnlyAChangedValue("LSZK");
        assertThat(migrated.getIcaoCode()).isEqualTo("LSZK");
    }

    @Test
    void replaceInOutboundPoints_attachesToParent_andDropsOriginals() {
        Location loc = newLoc();
        loc.replaceInOutboundPoints(List.of(
                Location.newInOutboundPoint("First", "INBOUND", "N", null),
                Location.newInOutboundPoint("Second", "OUTBOUND", "S", null)));
        assertThat(loc.getInOutboundPoints()).hasSize(2);

        loc.replaceInOutboundPoints(List.of(
                Location.newInOutboundPoint("Only one", "INBOUND", "W", null)));
        assertThat(loc.getInOutboundPoints()).hasSize(1);
        assertThat(loc.getInOutboundPoints().get(0).getPointName()).isEqualTo("Only one");
    }

    @Test
    void softDelete_isIdempotent() {
        Location loc = newLoc();
        loc.softDelete(null, Clock.systemUTC());
        assertThat(loc.isDeleted()).isTrue();
        loc.softDelete(null, Clock.systemUTC());
        assertThat(loc.isDeleted()).isTrue();
    }

    @Test
    void softDelete_preservesIcaoForAuditTrail() {
        Location loc = newLoc();
        loc.setIcaoValidatingOnlyAChangedValue("LSZH");
        loc.softDelete(null, Clock.systemUTC());
        assertThat(loc.getIcaoCode())
                .as("ICAO must survive soft-delete for the audit trail S-027 will surface")
                .isEqualTo("LSZH");
    }

    private static Location locationCarryingTheLegacyIcaoTheMigrationWrote(String legacyIcao) {
        Location loc = newLoc();
        try {
            Field field = Location.class.getDeclaredField("icaoCode");
            field.setAccessible(true);
            field.set(loc, legacyIcao);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Location.icaoCode is the field the migration writes by SQL; this test plants "
                            + "the same value the aggregate cannot accept through its own API", e);
        }
        return loc;
    }

    private static Location newLoc() {
        return Location.create(
                "Test Loc", null,
                CH, GRASS,
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                false, false, false);
    }
}
