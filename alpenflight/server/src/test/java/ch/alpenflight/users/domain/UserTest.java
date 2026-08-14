package ch.alpenflight.users.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final UUID CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID SUB = UUID.fromString("9d08ed9c-699a-4c26-9036-9f0bd378009d");
    private static final UUID LANG = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    private static User newUser() {
        return User.register(CLUB, SUB, "clubadmin1", "Club Admin", "clubadmin1@example.com",
                LANG, null);
    }

    @Test
    void register_normalises_text_and_pins_identity_columns() {
        User u = User.register(CLUB, SUB, "  jane.doe ", "  Jane Doe ", " jane@example.com ",
                LANG, null);
        assertThat(u.getUsername()).isEqualTo("jane.doe");
        assertThat(u.getFriendlyName()).isEqualTo("Jane Doe");
        assertThat(u.getNotificationEmail()).isEqualTo("jane@example.com");
        assertThat(u.getKeycloakSub()).isEqualTo(SUB);
        assertThat(u.getClubId()).isEqualTo(CLUB);
    }

    @Test
    void register_rejects_blank_required_fields() {
        assertThatThrownBy(() ->
                User.register(CLUB, SUB, " ", "ok", "ok@example.com", LANG, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
        assertThatThrownBy(() ->
                User.register(CLUB, SUB, "ok", " ", "ok@example.com", LANG, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("friendlyName");
    }

    @Test
    void update_profile_does_not_touch_identity_binding_fields() {
        User u = newUser();
        u.updateProfile("Renamed", "new@example.com", "+41 79 000 00 00", "vip",
                LANG);
        assertThat(u.getFriendlyName()).isEqualTo("Renamed");
        assertThat(u.getNotificationEmail()).isEqualTo("new@example.com");
        assertThat(u.getPhoneNumber()).isEqualTo("+41 79 000 00 00");
        assertThat(u.getRemarks()).isEqualTo("vip");
        assertThat(u.getUsername()).isEqualTo("clubadmin1");
        assertThat(u.getKeycloakSub()).isEqualTo(SUB);
        assertThat(u.getClubId()).isEqualTo(CLUB);
    }

    @Test
    void soft_delete_is_idempotent_and_stamps_actor() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-26T10:00:00Z"), ZoneOffset.UTC);
        UUID actor = UUID.randomUUID();
        User u = newUser();
        assertThat(u.isActive()).isTrue();
        u.softDelete(actor, fixed);
        Instant firstDeletedOn = u.getDeletedOn();
        assertThat(firstDeletedOn).isEqualTo(Instant.parse("2026-05-26T10:00:00Z"));
        assertThat(u.isActive()).isFalse();
        Clock later = Clock.fixed(Instant.parse("2026-05-26T11:00:00Z"), ZoneOffset.UTC);
        u.softDelete(UUID.randomUUID(), later);
        assertThat(u.getDeletedOn()).isEqualTo(firstDeletedOn);
    }

    @Test
    void assign_and_unlink_person() {
        User u = newUser();
        UUID pn = UUID.randomUUID();
        u.assignToPerson(pn);
        assertThat(u.getPersonId()).isEqualTo(pn);
        u.unlinkPerson();
        assertThat(u.getPersonId()).isNull();
    }
}
