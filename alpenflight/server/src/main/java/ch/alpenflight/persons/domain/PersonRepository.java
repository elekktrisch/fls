package ch.alpenflight.persons.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link Person} persistence. Implemented by
 * {@code ch.alpenflight.persons.infra.JpaPersonRepository}.
 *
 * <p><strong>No {@code findAll} method exists by design.</strong> Person is
 * cross-tenant; a naive global list would leak every Person across every
 * tenant. The only legal reads are:
 *
 * <ul>
 *   <li>{@link #findActiveListRowsInCurrentTenant} — JOIN through
 *       {@code t_person_club}; Hibernate's {@code @TenantId} auto-appends
 *       the tenant predicate on the junction.</li>
 *   <li>{@link #findActiveById} — by PK (cross-tenant); used by sysadmin
 *       cross-cutting paths + by Flight crew load (S-058) where the
 *       caller tenant differs from the Person's home.</li>
 *   <li>{@link #findActiveByEmail} + {@link #findActiveByIdentityTriple} —
 *       exact-match add-existing lookup (security plan row 1).</li>
 * </ul>
 */
public interface PersonRepository {

    /**
     * Projection row for the {@code GET /api/v1/persons} list view. Carries
     * the caller-tenant's {@code t_person_club} columns (memberNumber,
     * memberStateId, role flags), the Person identity fields, and an opaque
     * {@code activeMembershipCount} so the response can show
     * {@code inOtherClubsCount = total - tenant-visible}.
     *
     * <p>The query joins {@code t_person} × {@code t_person_club}; Hibernate
     * filters {@code pc.club_id = :currentTenant} automatically because
     * {@link PersonClub#getClubId()} carries {@code @TenantId}.
     */
    record ListRow(UUID id,
                   String firstname,
                   String lastname,
                   @Nullable String emailPrivate,
                   @Nullable String mobilePhone,
                   @Nullable String city,
                   @Nullable String zip,
                   @Nullable String memberNumber,
                   @Nullable UUID memberStateId,
                   @Nullable String memberStateName,
                   boolean active,
                   boolean motorPilot,
                   boolean towPilot,
                   boolean gliderInstructor,
                   boolean gliderPilot,
                   boolean gliderTrainee,
                   boolean winchOperator,
                   boolean motorInstructor) {}

    List<ListRow> findActiveListRowsInCurrentTenant();

    /**
     * Look up a Person by PK regardless of tenant scope. Hibernate's
     * {@code @TenantId} on {@link PersonClub#getClubId()} still scopes the
     * {@code personClubs} collection on the returned aggregate to the
     * caller's tenant — so a CLUB_ADMIN sees only their tenant's
     * memberships; sysadmin via {@code Tenants.runAs} or unscoped session
     * sees all.
     */
    Optional<Person> findActiveById(UUID id);

    /** Caller-tenant-scoped existence check: does this Person have an alive PersonClub in the caller's tenant? */
    boolean hasActiveMembershipInCurrentTenant(UUID personId);

    /** Cross-tenant existence check: does this Person have any alive PersonClub in any other tenant? */
    boolean hasActiveMembershipInOtherTenant(UUID personId, UUID currentTenantId);

    /** Counts all alive memberships across every tenant; used to derive {@code inOtherClubsCount}. */
    long countActiveMembershipsAcrossTenants(UUID personId);

    /** Exact-match by lower-cased email; matches either {@code email_private} or {@code email_business}. */
    List<Person> findActiveByEmail(String lowerCasedEmail);

    /** Exact-match by identity triple. Birthday is required (no two-of-three fallback). */
    List<Person> findActiveByIdentityTriple(String firstname, String lastname, LocalDate birthday);

    Person save(Person person);

    void flush();
}
