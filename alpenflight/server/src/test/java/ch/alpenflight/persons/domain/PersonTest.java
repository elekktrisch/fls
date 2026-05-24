package ch.alpenflight.persons.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Aggregate-level invariants for {@link Person}. The sacred-cow shape lives
 * in the {@code joinClub} / {@code leaveClub} / {@code softDelete} methods;
 * these tests pin the rules that the schema deliberately does NOT enforce
 * (per ADR 0022 directive 2: business rules on aggregates, not DB).
 */
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

        // Reactivation preserves identity: the partial unique ux_person_club_alive
        // would reject a fresh insert; reactivating the prior row flips deleted_on
        // back to NULL and re-applies the membership fields.
        assertThat(rejoined).isSameAs(original);
        assertThat(rejoined.isDeleted()).isFalse();
        assertThat(rejoined.getMemberNumber()).isEqualTo("M-1-new");
    }

    @Test
    void softDelete_refusesWhenOtherTenantHasActiveMembership() {
        Person p = Person.register("Ada", "Lovelace", null);
        p.joinClub(CLUB_A, "M-1", null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        // CLUB_A admin attempts to soft-delete; the caller-side check
        // (`hasActiveMembershipInOtherTenant`) reports CLUB_B still has an
        // active membership for this Person — the aggregate must refuse.
        assertThatThrownBy(() -> p.softDelete(null, Clock.systemUTC(), /* hasOtherTenantMemberships = */ true))
                .as("CLUB_ADMIN must not orphan another tenant's PersonClub records via single-tenant delete")
                .isInstanceOf(CrossTenantMembershipBlockedException.class);
        assertThat(p.isDeleted()).isFalse();
    }

    @Test
    void softDelete_succeedsWhenNoOtherTenantHasActiveMembership() {
        Person p = Person.register("Ada", "Lovelace", null);
        p.joinClub(CLUB_A, "M-1", null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);

        p.softDelete(null, Clock.systemUTC(), /* hasOtherTenantMemberships = */ false);

        assertThat(p.isDeleted()).isTrue();
        // Cascade soft-deletes the aggregate-internal PersonClub rows so the
        // tenant-scoped view drops the membership immediately.
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
}
