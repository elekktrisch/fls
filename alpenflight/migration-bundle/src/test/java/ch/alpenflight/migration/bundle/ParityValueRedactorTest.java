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
}
