package ch.alpenflight.clubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClubDomainTest {

    private static final UUID CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");

    @Test
    void rename_trims_and_rejects_blank() {
        Club club = Club.create("Old", "old-club", "OLD", false, CH, ACTIVE);
        club.rename("  Mountain Soaring  ");
        assertThat(club.getClubname()).isEqualTo("Mountain Soaring");

        assertThatThrownBy(() -> club.rename("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rebrand_rejects_uppercase_and_special_chars() {
        Club club = Club.create("Mountain Soaring", "ms-club", "MS", false, CH, ACTIVE);

        assertThatThrownBy(() -> club.rebrand("Bad-Slug"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> club.rebrand("bad slug"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> club.rebrand("bad@slug"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rebrand_enforces_3_to_64_length_bounds() {
        Club club = Club.create("Mountain Soaring", "ms-club", "MS", false, CH, ACTIVE);

        assertThatThrownBy(() -> club.rebrand("ab"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> club.rebrand("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);

        club.rebrand("abc");
        assertThat(club.getSlug()).isEqualTo("abc");

        String maxOk = "a".repeat(64);
        club.rebrand(maxOk);
        assertThat(club.getSlug()).isEqualTo(maxOk);
    }

    @Test
    void publicRegistration_toggles_via_aggregate_methods() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE);
        assertThat(club.isPublicRegistrationEnabled()).isFalse();
        club.enablePublicRegistration();
        assertThat(club.isPublicRegistrationEnabled()).isTrue();
        club.disablePublicRegistration();
        assertThat(club.isPublicRegistrationEnabled()).isFalse();
    }
}
