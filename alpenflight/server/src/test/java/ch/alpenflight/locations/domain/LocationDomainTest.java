package ch.alpenflight.locations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationDomainTest {

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
        assertThatThrownBy(() -> loc.setIcao("abcd"))
                .isInstanceOf(IcaoCodeInvalidException.class);
    }

    @Test
    void setIcao_rejects_wrongLength_throwsDomainException() {
        Location loc = newLoc();
        assertThatThrownBy(() -> loc.setIcao("ABC"))
                .isInstanceOf(IcaoCodeInvalidException.class);
        assertThatThrownBy(() -> loc.setIcao("ABCDE"))
                .isInstanceOf(IcaoCodeInvalidException.class);
    }

    @Test
    void setIcao_accepts_fourLetter_andTwoLetterTwoDigit() {
        Location loc = newLoc();
        loc.setIcao("LSZH");
        assertThat(loc.getIcaoCode()).isEqualTo("LSZH");
        loc.setIcao("AB12");
        assertThat(loc.getIcaoCode()).isEqualTo("AB12");
    }

    @Test
    void setIcao_acceptsNull_andBlankBecomesNull() {
        Location loc = newLoc();
        loc.setIcao(null);
        assertThat(loc.getIcaoCode()).isNull();
        loc.setIcao("   ");
        assertThat(loc.getIcaoCode()).isNull();
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
        loc.setIcao("LSZH");
        loc.softDelete(null, Clock.systemUTC());
        assertThat(loc.getIcaoCode())
                .as("ICAO must survive soft-delete for the audit trail S-027 will surface")
                .isEqualTo("LSZH");
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
