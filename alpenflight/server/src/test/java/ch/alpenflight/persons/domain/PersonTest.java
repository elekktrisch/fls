package ch.alpenflight.persons.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a2");

    @Test
    void joinClub_rejectsSecondActiveMembershipForSameClub() {
        Person p = Person.register("Ada", "Lovelace", null);
        p.joinClub(CLUB_A, "M-1", null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        assertThatThrownBy(() -> p.joinClub(
                        CLUB_A, "M-1", null,
                        PersonRoleFlags.none(), PersonNotificationPrefs.none(), true))
                .as("two alive PersonClub rows for the same (person, club) pair is the structural invariant"
                        + " of ux_person_club_alive — the aggregate must reject before reaching the DB")
                .isInstanceOf(DuplicateClubMembershipException.class);
    }

    @Test
    void joinClub_reactivatesSoftDeletedMembershipInPlace() {
        Person p = Person.register("Ada", "Lovelace", null);
        PersonClub original = p.joinClub(
                CLUB_A, "M-1", null,
                PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
        p.leaveClub(CLUB_A, null, Clock.systemUTC());
        assertThat(original.isDeleted()).isTrue();

        PersonClub rejoined = p.joinClub(
                CLUB_A, "M-1-new", null,
                PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        assertThat(rejoined).isSameAs(original);
        assertThat(rejoined.isDeleted()).isFalse();
        assertThat(rejoined.getMemberNumber()).isEqualTo("M-1-new");
    }

    @Test
    void softDelete_refusesWhenOtherTenantHasActiveMembership() {
        Person p = Person.register("Ada", "Lovelace", null);
        p.joinClub(CLUB_A, "M-1", null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        assertThatThrownBy(() -> p.softDelete(null, Clock.systemUTC(), true))
                .as("CLUB_ADMIN must not orphan another tenant's PersonClub records via single-tenant delete")
                .isInstanceOf(CrossTenantMembershipBlockedException.class);
        assertThat(p.isDeleted()).isFalse();
    }

    @Test
    void softDelete_succeedsWhenNoOtherTenantHasActiveMembership() {
        Person p = Person.register("Ada", "Lovelace", null);
        p.joinClub(CLUB_A, "M-1", null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        p.softDelete(null, Clock.systemUTC(), false);

        assertThat(p.isDeleted()).isTrue();
        assertThat(p.getActivePersonClubs()).isEmpty();
    }

    @Test
    void roleFlagsAreIndependent() {
        Person p = Person.register("Ada", "Lovelace", null);
        PersonClub pc = p.joinClub(
                CLUB_A, "M-1", null,
                new PersonRoleFlags(false, false, false, true, false, false, false, false),
                PersonNotificationPrefs.none(),
                true);

        assertThat(pc.isGliderPilot()).isTrue();
        assertThat(pc.isMotorPilot()).isFalse();
        assertThat(pc.isTowPilot()).isFalse();
    }

    @Test
    void updateNotificationPrefs_changesOnlyTheThreeBooleans_adminFieldsUntouched() {
        Person p = Person.register("Ada", "Lovelace", null);
        p.joinClub(
                CLUB_A, "M-42", UUID.fromString("019e30c3-2c00-7001-8000-0000000000b1"),
                new PersonRoleFlags(true, true, true, true, false, false, false, false),
                new PersonNotificationPrefs(false, false, false),
                true);

        PersonClub pc = p.updateNotificationPrefs(
                CLUB_A, new PersonNotificationPrefs(true, false, true));

        assertThat(pc.isReceiveFlightReports()).isTrue();
        assertThat(pc.isReceiveAircraftReservationNotifications()).isFalse();
        assertThat(pc.isReceivePlanningDayRoleReminder()).isTrue();
        assertThat(pc.getMemberNumber()).isEqualTo("M-42");
        assertThat(pc.getMemberStateId())
                .isEqualTo(UUID.fromString("019e30c3-2c00-7001-8000-0000000000b1"));
        assertThat(pc.isMotorPilot()).isTrue();
        assertThat(pc.isTowPilot()).isTrue();
        assertThat(pc.isGliderInstructor()).isTrue();
        assertThat(pc.isGliderPilot()).isTrue();
        assertThat(pc.isActive()).isTrue();
    }

    @Test
    void updateNotificationPrefs_withNoMembershipInClub_throws() {
        Person p = Person.register("Grace", "Hopper", null);
        p.joinClub(CLUB_A, "M-1", null,
                PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        assertThatThrownBy(() -> p.updateNotificationPrefs(
                        CLUB_B, new PersonNotificationPrefs(true, true, true)))
                .isInstanceOf(PersonNotFoundException.class);
    }
}
