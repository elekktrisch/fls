package ch.alpenflight.clubs;

import ch.alpenflight.platform.id.ClubId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Club aggregate root. Mapped to the V2 {@code club} table extended by V5
 * with {@code slug} + {@code public_registration_enabled}.
 *
 * <p>Per ADR 0022 directive 2 the business rules (slug format, blank-name
 * rejection) live on the aggregate. The schema enforces only structure: PK,
 * partial UNIQUE on {@code slug}, NOT NULL on {@code clubname} /
 * {@code club_key} / {@code country_id} / {@code club_state_id}.
 *
 * <p>Many V2 columns (address, phone, FK to country / club_state, audit cols,
 * etc.) are intentionally NOT mapped on this aggregate today — S-048 is a
 * walking skeleton and the DTO surface is narrow. Future stories that need to
 * read/write those columns will extend the entity.
 */
@Entity
@Table(name = "club")
public class Club {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,64}$");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CLUB_KEY_LENGTH = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "clubname", nullable = false, length = MAX_NAME_LENGTH)
    private String clubname = "";

    @Column(name = "club_key", nullable = false, length = MAX_CLUB_KEY_LENGTH)
    private String clubKey = "";

    @Column(name = "slug", length = 64)
    private @Nullable String slug;

    @Column(name = "public_registration_enabled", nullable = false)
    private boolean publicRegistrationEnabled;

    // V2 NOT NULL FKs that the walking-skeleton DTO does not surface. Mapped
    // so update operations don't null them; not exposed as setters.
    @Column(name = "country_id", nullable = false)
    private @Nullable UUID countryId;

    @Column(name = "club_state_id", nullable = false)
    private @Nullable UUID clubStateId;

    @Column(name = "deleted_on")
    private java.time.@Nullable Instant deletedOn;

    protected Club() {
        // JPA.
    }

    public static Club create(String name, String slug, String clubKey,
                              boolean publicRegistrationEnabled,
                              UUID countryId, UUID clubStateId) {
        Club club = new Club();
        club.rename(name);
        club.rebrand(slug);
        club.setClubKey(clubKey);
        club.publicRegistrationEnabled = publicRegistrationEnabled;
        club.countryId = countryId;
        club.clubStateId = clubStateId;
        return club;
    }

    public void rename(String newName) {
        String trimmed = newName == null ? "" : newName.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Club name must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Club name exceeds %d characters".formatted(MAX_NAME_LENGTH));
        }
        this.clubname = trimmed;
    }

    public void rebrand(String newSlug) {
        if (newSlug == null || !SLUG_PATTERN.matcher(newSlug).matches()) {
            throw new IllegalArgumentException(
                    "Slug must match ^[a-z0-9-]{3,64}$, got: " + newSlug);
        }
        this.slug = newSlug;
    }

    public void enablePublicRegistration() {
        this.publicRegistrationEnabled = true;
    }

    public void disablePublicRegistration() {
        this.publicRegistrationEnabled = false;
    }

    public void softDelete(Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
        }
    }

    private void setClubKey(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Club key must not be blank");
        }
        if (trimmed.length() > MAX_CLUB_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Club key exceeds %d characters".formatted(MAX_CLUB_KEY_LENGTH));
        }
        this.clubKey = trimmed;
    }

    /**
     * Returns the typed {@link ClubId} wrapper around the persistence-layer
     * {@code UUID}. The field stays raw {@code UUID} so JPA / Hibernate /
     * Spring Data work without converters; the getter is the seam where the
     * value leaves the aggregate, so it's the place to type it.
     */
    public @Nullable ClubId getId() {
        return ClubId.ofNullable(id);
    }

    /**
     * Persistence-layer raw {@code UUID} accessor, for internal callers that
     * need the value in its repository-key form (e.g. {@code findActiveById}).
     * External callers should use {@link #getId()}.
     */
    @Nullable UUID getRawId() {
        return id;
    }

    public String getClubname() {
        return clubname;
    }

    public String getClubKey() {
        return clubKey;
    }

    public @Nullable String getSlug() {
        return slug;
    }

    public boolean isPublicRegistrationEnabled() {
        return publicRegistrationEnabled;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
