package ch.alpenflight.users.domain;

import ch.alpenflight.platform.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * User aggregate root — the principal-subject row that pairs an internal
 * {@code user.id} with a Keycloak {@code sub}.
 *
 * <p><strong>Cross-tenant.</strong> No {@code @TenantId}: scoping the
 * principal through Hibernate's tenant filter would chicken-and-egg the
 * JWT-to-tenant resolution path. CLUB_ADMIN scoping for tenant-scoped HTTP
 * endpoints is enforced explicitly via {@code WHERE u.club_id = :callerClub}
 * in the repository.
 *
 * <p>Identity-binding fields ({@code keycloakSub}, {@code clubId}) are
 * immutable post-create:
 * <ul>
 *   <li>{@code keycloakSub} pairs the FLS row to the KC identity; flipping
 *       it would orphan the JWT-to-row lookup.</li>
 *   <li>{@code clubId} is the home club. "Move to a different club" is
 *       expressed as soft-delete + recreate; in-place rebind would silently
 *       relocate the user's audit history.</li>
 * </ul>
 * Per ADR 0022 directive 2 these invariants live here (Java) not in DB
 * triggers or CHECK constraints.
 *
 * <p><strong>Roles are not on the aggregate.</strong> They live in Keycloak;
 * the application reads them from {@code realm_access.roles} on the JWT and
 * writes them via the KC admin REST API.
 */
@Entity
// `user` is a Postgres reserved word; the table uses the `t_user` name
// instead of relying on quoting (broader t_ prefix convention is a
// follow-up). V2 DDL matches.
@Table(name = "t_user")
public class User {

    private static final int MAX_USERNAME_LENGTH = 256;
    private static final int MAX_FRIENDLY_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 256;
    private static final int MAX_PHONE_LENGTH = 30;
    private static final int MAX_REMARKS_LENGTH = 250;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "club_id", nullable = false, updatable = false)
    private UUID clubId;

    @Column(name = "username", nullable = false, length = MAX_USERNAME_LENGTH)
    private String username = "";

    @Column(name = "friendly_name", nullable = false, length = MAX_FRIENDLY_NAME_LENGTH)
    private String friendlyName = "";

    @Column(name = "person_id")
    private @Nullable UUID personId;

    @Column(name = "notification_email", nullable = false, length = MAX_EMAIL_LENGTH)
    private String notificationEmail = "";

    @Column(name = "phone_number", length = MAX_PHONE_LENGTH)
    private @Nullable String phoneNumber;

    @Column(name = "remarks", length = MAX_REMARKS_LENGTH)
    private @Nullable String remarks;

    @Column(name = "language_id", nullable = false)
    private UUID languageId;

    @Column(name = "keycloak_sub", updatable = false)
    private @Nullable UUID keycloakSub;

    @Column(name = "created_on", nullable = false)
    private Instant createdOn = Instant.EPOCH;

    @Column(name = "created_by_user_id")
    private @Nullable UUID createdByUserId;

    @Column(name = "modified_on", nullable = false)
    private Instant modifiedOn = Instant.EPOCH;

    @Column(name = "modified_by_user_id")
    private @Nullable UUID modifiedByUserId;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    private @Nullable UUID deletedByUserId;

    /** JPA no-arg constructor — package-private; do not call from app code. */
    protected User() {
        this.clubId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        this.languageId = UUID.fromString("00000000-0000-0000-0000-000000000000");
    }

    /**
     * Register a new User. {@code keycloakSub} is required at create time
     * (admin-invite flow obtains it from KC immediately; JIT-on-login flow
     * has the JWT's {@code sub}).
     */
    public static User register(UUID clubId,
                                UUID keycloakSub,
                                String username,
                                String friendlyName,
                                String notificationEmail,
                                UUID languageId,
                                @Nullable UUID personId) {
        User u = new User();
        u.clubId = requireNonNull(clubId, "clubId");
        u.keycloakSub = requireNonNull(keycloakSub, "keycloakSub");
        u.username = requireText(username, "username", MAX_USERNAME_LENGTH);
        u.friendlyName = requireText(friendlyName, "friendlyName", MAX_FRIENDLY_NAME_LENGTH);
        u.notificationEmail = requireText(notificationEmail, "notificationEmail", MAX_EMAIL_LENGTH);
        u.languageId = requireNonNull(languageId, "languageId");
        u.personId = personId;
        return u;
    }

    public @Nullable UserId getId() {
        return id == null ? null : UserId.of(id);
    }

    public UUID getClubId() {
        return clubId;
    }

    public String getUsername() {
        return username;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public @Nullable String getPhoneNumber() {
        return phoneNumber;
    }

    public @Nullable String getRemarks() {
        return remarks;
    }

    public UUID getLanguageId() {
        return languageId;
    }

    public @Nullable UUID getKeycloakSub() {
        return keycloakSub;
    }

    public @Nullable UUID getPersonId() {
        return personId;
    }

    public @Nullable Instant getDeletedOn() {
        return deletedOn;
    }

    public boolean isActive() {
        return deletedOn == null;
    }

    /**
     * Update the mutable identity / contact / language fields. Username,
     * club, and Keycloak sub are intentionally absent from the parameter
     * list — they are identity-binding and may not be re-bound through this
     * surface.
     */
    public void updateProfile(String friendlyName,
                              String notificationEmail,
                              @Nullable String phoneNumber,
                              @Nullable String remarks,
                              UUID languageId) {
        this.friendlyName = requireText(friendlyName, "friendlyName", MAX_FRIENDLY_NAME_LENGTH);
        this.notificationEmail = requireText(notificationEmail, "notificationEmail", MAX_EMAIL_LENGTH);
        this.phoneNumber = optionalText(phoneNumber, "phoneNumber", MAX_PHONE_LENGTH);
        this.remarks = optionalText(remarks, "remarks", MAX_REMARKS_LENGTH);
        this.languageId = requireNonNull(languageId, "languageId");
    }

    /**
     * Bind this User to a Person row. Service layer asserts the Person
     * belongs to the User's club via the S-051 lookup pattern.
     */
    public void assignToPerson(UUID personId) {
        this.personId = requireNonNull(personId, "personId");
    }

    public void unlinkPerson() {
        this.personId = null;
    }

    /**
     * Soft-delete. Pairs with a Keycloak {@code enabled=false} flip at the
     * service layer — KC stays as the source-of-truth event log; we never
     * hard-delete the KC user.
     */
    public void softDelete(@Nullable UUID actorUserId, Clock clock) {
        if (deletedOn != null) {
            return;
        }
        this.deletedOn = clock.instant();
        this.deletedByUserId = actorUserId;
    }

    private static <T> T requireNonNull(@Nullable T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static String requireText(@Nullable String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    field + " length must be ≤ " + max + " (got " + trimmed.length() + ")");
        }
        return trimmed;
    }

    private static @Nullable String optionalText(@Nullable String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    field + " length must be ≤ " + max + " (got " + trimmed.length() + ")");
        }
        return trimmed;
    }
}
