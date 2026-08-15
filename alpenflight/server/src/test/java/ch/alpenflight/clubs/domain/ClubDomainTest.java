package ch.alpenflight.clubs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClubDomainTest {

    private static final UUID CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID DEPLOYMENT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void rename_trims_and_rejects_blank() {
        Club club = Club.create("Old", "old-club", "OLD", false, CH, ACTIVE, DEPLOYMENT);
        club.rename("  Mountain Soaring  ");
        assertThat(club.getClubname()).isEqualTo("Mountain Soaring");

        assertThatThrownBy(() -> club.rename("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rebrand_rejects_uppercase_and_special_chars() {
        Club club = Club.create("Mountain Soaring", "ms-club", "MS", false, CH, ACTIVE, DEPLOYMENT);

        assertThatThrownBy(() -> club.rebrand("Bad-Slug"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> club.rebrand("bad slug"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> club.rebrand("bad@slug"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rebrand_enforces_3_to_64_length_bounds() {
        Club club = Club.create("Mountain Soaring", "ms-club", "MS", false, CH, ACTIVE, DEPLOYMENT);

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
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.isPublicRegistrationEnabled()).isFalse();
        club.enablePublicRegistration();
        assertThat(club.isPublicRegistrationEnabled()).isTrue();
        club.disablePublicRegistration();
        assertThat(club.isPublicRegistrationEnabled()).isFalse();
    }

    @Test
    void planningDayOk_rule_sends_ok_when_day_has_a_reservation_regardless_of_flag() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.planningDayMailsAsOkWhenNoReservation()).isFalse();
        assertThat(club.shouldSendPlanningDayOk(true)).isTrue();
    }

    @Test
    void planningDayOk_rule_cancels_when_no_reservation_and_flag_false() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.shouldSendPlanningDayOk(false)).isFalse();
    }

    @Test
    void planningDayOk_rule_sends_ok_when_flag_true_even_with_no_reservation() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        club.setPlanningDayMailsAsOkWhenNoReservation(true);
        assertThat(club.planningDayMailsAsOkWhenNoReservation()).isTrue();
        assertThat(club.shouldSendPlanningDayOk(false)).isTrue();
        assertThat(club.shouldSendPlanningDayOk(true)).isTrue();
    }

    @Test
    void rotateJoinCode_sets_a_fresh_code_from_the_alphabet() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        JoinCodeGenerator generator = () -> "ABCD2345";

        String code = club.rotateJoinCode(generator, candidate -> true);

        assertThat(code).isEqualTo("ABCD2345");
        assertThat(club.getJoinCode()).isEqualTo("ABCD2345");
        assertThat(code).hasSize(JoinCodeGenerator.LENGTH)
                .matches("[" + JoinCodeGenerator.ALPHABET_WITHOUT_AMBIGUOUS_GLYPHS + "]+");
    }

    @Test
    void rotateJoinCode_redraws_until_a_globally_unique_code_is_found() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        Deque<String> candidates = new ArrayDeque<>(java.util.List.of("TAKEN111", "TAKEN222", "FREE3456"));
        JoinCodeGenerator generator = candidates::pop;

        String code = club.rotateJoinCode(generator, candidate -> candidate.startsWith("FREE"));

        assertThat(code).isEqualTo("FREE3456");
        assertThat(club.getJoinCode()).isEqualTo("FREE3456");
    }

    @Test
    void rotateJoinCode_gives_up_when_every_candidate_collides() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        JoinCodeGenerator generator = () -> "ALWAYSXX";

        assertThatThrownBy(() -> club.rotateJoinCode(generator, candidate -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not mint a unique");
    }

    @Test
    void publicDisplay_stores_city_and_logo_and_normalizes_blank_to_null() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.getCity()).isNull();
        assertThat(club.getLogoUrl()).isNull();

        club.setPublicDisplay("  Zurich  ", "  https://example.com/logo.png  ");
        assertThat(club.getCity()).isEqualTo("Zurich");
        assertThat(club.getLogoUrl()).isEqualTo("https://example.com/logo.png");

        club.setPublicDisplay("   ", null);
        assertThat(club.getCity()).isNull();
        assertThat(club.getLogoUrl()).isNull();
    }

    @Test
    void registrationOperatorEmails_canonicalize_a_delimited_list_to_comma_joined() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);

        club.setRegistrationOperatorEmails(
                " Schnupper@club.example; ops@club.example , Second@club.example ",
                "mitflug@club.example");

        assertThat(club.getDiscoveryFlightOperatorEmail())
                .isEqualTo("schnupper@club.example,ops@club.example,second@club.example");
        assertThat(club.getScenicFlightOperatorEmail()).isEqualTo("mitflug@club.example");
        assertThat(club.notifiesDiscoveryFlightOperator()).isTrue();
        assertThat(club.notifiesScenicFlightOperator()).isTrue();
    }

    @Test
    void registrationOperatorEmails_stay_optional_so_an_unset_address_only_skips_the_organiser_mail() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.getDiscoveryFlightOperatorEmail()).isNull();
        assertThat(club.notifiesDiscoveryFlightOperator()).isFalse();
        assertThat(club.notifiesScenicFlightOperator()).isFalse();

        club.setRegistrationOperatorEmails("ops@club.example", "ops@club.example");
        club.setRegistrationOperatorEmails("   ", null);

        assertThat(club.getDiscoveryFlightOperatorEmail()).isNull();
        assertThat(club.getScenicFlightOperatorEmail()).isNull();
        assertThat(club.notifiesDiscoveryFlightOperator()).isFalse();
        assertThat(club.notifiesScenicFlightOperator()).isFalse();
    }

    @Test
    void registrationOperatorEmails_reject_a_malformed_address_anywhere_in_the_list() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);

        assertThatThrownBy(() -> club.setRegistrationOperatorEmails("ops@club.example,not-an-email", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discoveryFlightOperatorEmail");
        assertThatThrownBy(() -> club.setRegistrationOperatorEmails(null, "nobody@"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenicFlightOperatorEmail");
        assertThatThrownBy(() -> club.setRegistrationOperatorEmails(
                ("a@b.example," + "c@d.example,").repeat(20), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("250");

        assertThat(club.getDiscoveryFlightOperatorEmail()).isNull();
        assertThat(club.getScenicFlightOperatorEmail()).isNull();
    }

    @Test
    void discoveryFlightType_is_optional_and_clearable() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.getDiscoveryFlightTypeId()).isNull();

        UUID flightType = UUID.fromString("019e30c3-2c00-7001-8000-0000000000f1");
        club.setDiscoveryFlightType(flightType);
        assertThat(club.getDiscoveryFlightTypeId()).isEqualTo(flightType);

        club.setDiscoveryFlightType(null);
        assertThat(club.getDiscoveryFlightTypeId()).isNull();
    }

    @Test
    void homebase_accepts_a_location_the_club_owns() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.getHomebaseId()).isNull();

        UUID ownLocation = UUID.fromString("019e30c3-2c00-7001-8000-00000000a001");
        club.relocateHomebase(ownLocation, ownLocation::equals);
        assertThat(club.getHomebaseId()).isEqualTo(ownLocation);
    }

    @Test
    void homebase_rejects_a_location_the_club_does_not_own() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        UUID ownLocation = UUID.fromString("019e30c3-2c00-7001-8000-00000000a001");
        club.relocateHomebase(ownLocation, ownLocation::equals);

        UUID foreignLocation = UUID.fromString("019e30c3-2c00-7001-8000-00000000b002");
        assertThatThrownBy(() -> club.relocateHomebase(foreignLocation, ownLocation::equals))
                .isInstanceOf(InvalidClubReferenceException.class)
                .extracting(e -> ((InvalidClubReferenceException) e).getField())
                .isEqualTo("homebaseId");
        assertThat(club.getHomebaseId())
                .as("a rejected write leaves the previous homebase intact")
                .isEqualTo(ownLocation);
    }

    @Test
    void homebase_clears_on_null_without_consulting_the_lookup() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        UUID ownLocation = UUID.fromString("019e30c3-2c00-7001-8000-00000000a001");
        club.relocateHomebase(ownLocation, ownLocation::equals);

        club.relocateHomebase(null, id -> {
            throw new AssertionError("clearing must not query for ownership");
        });
        assertThat(club.getHomebaseId()).isNull();
    }

    @Test
    void acceptsPublicRegistration_requires_the_optIn_flag() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.acceptsPublicRegistration()).isFalse();

        club.enablePublicRegistration();
        assertThat(club.acceptsPublicRegistration()).isTrue();

        club.disablePublicRegistration();
        assertThat(club.acceptsPublicRegistration()).isFalse();
    }

    @Test
    void acceptsPublicRegistration_is_false_for_a_softDeleted_club() {
        Club club = Club.create("X", "x-club", "X", true, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.acceptsPublicRegistration()).isTrue();

        club.softDelete(java.time.Clock.systemUTC());

        assertThat(club.isPublicRegistrationEnabled()).isTrue();
        assertThat(club.acceptsPublicRegistration()).isFalse();
    }

    @Test
    void isWellFormedSlug_matches_the_rebrand_rule() {
        assertThat(Club.isWellFormedSlug("alpine-soaring")).isTrue();
        assertThat(Club.isWellFormedSlug("abc")).isTrue();
        assertThat(Club.isWellFormedSlug("a".repeat(64))).isTrue();

        assertThat(Club.isWellFormedSlug(null)).isFalse();
        assertThat(Club.isWellFormedSlug("ab")).isFalse();
        assertThat(Club.isWellFormedSlug("a".repeat(65))).isFalse();
        assertThat(Club.isWellFormedSlug("Bad-Slug")).isFalse();
        assertThat(Club.isWellFormedSlug("../../etc/passwd")).isFalse();
    }

    @Test
    void planningNotification_optIn_tracks_a_nonBlank_recipient_address() {
        Club club = Club.create("X", "x-club", "X", false, CH, ACTIVE, DEPLOYMENT);
        assertThat(club.wantsPlanningDayNotifications()).isFalse();
        assertThat(club.getPlanningDayInfoMailTo()).isNull();

        club.setPlanningDayInfoMailTo("  ops@club.example  ");
        assertThat(club.getPlanningDayInfoMailTo()).isEqualTo("ops@club.example");
        assertThat(club.wantsPlanningDayNotifications()).isTrue();

        club.setPlanningDayInfoMailTo("   ");
        assertThat(club.getPlanningDayInfoMailTo()).isNull();
        assertThat(club.wantsPlanningDayNotifications()).isFalse();
    }
}
