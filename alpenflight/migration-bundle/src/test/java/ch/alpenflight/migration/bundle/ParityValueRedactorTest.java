package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ParityValueRedactorTest {

    @Test
    void sentinelNonPiiColumnEmitsItsValue() {
        String emitted = ParityValueRedactor.emit(
                EntityType.USER, "club_id", "c0ffee", Set.of("club_id"));
        assertThat(emitted).isEqualTo("c0ffee");
    }

    @Test
    void piiColumnRedactsEvenWhenMarkedSentinel() {
        String emitted = ParityValueRedactor.emit(
                EntityType.USER, "username", "jdoe", Set.of("username"));
        assertThat(emitted).isEqualTo(ParityValueRedactor.REDACTED);
    }

    @Test
    void nonSentinelColumnRedactsByDefault() {
        // Fail-closed: a column nobody opted into the value diff for (and that
        // a future mapper might quietly add) never has its value emitted.
        String emitted = ParityValueRedactor.emit(
                EntityType.USER, "remarks", "secret note", Set.of());
        assertThat(emitted).isEqualTo(ParityValueRedactor.REDACTED);
    }

    @Test
    void personIdentityColumnsAreClassifiedPii() {
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "firstname")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "lastname")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "email_private")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "licence_number")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "country_id")).isFalse();
    }

    @Test
    void piiSetIsASupersetOfTheAuthoritativeTenantRulesColumns() {
        // Every tenant-rules.yaml pii_column for a mapped table must be covered,
        // else a future sentinel marking on one of these would leak its value.
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "company_name")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "address_line1")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "address_line2")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "zip")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "city")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "region")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "birthday")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON, "spot_link")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.USER, "remarks")).isTrue();
        assertThat(ParityValueRedactor.isPii(EntityType.PERSON_CLUB, "member_number")).isTrue();
    }
}
