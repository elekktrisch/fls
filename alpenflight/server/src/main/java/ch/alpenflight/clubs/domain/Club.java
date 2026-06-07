package ch.alpenflight.clubs.domain;

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
 * Club aggregate root. Mapped to the V2 {@code t_club} table extended by V5
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
@Table(name = "t_club")
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

    // J-6 T-10b planning-notification fields (V35). The address the
    // PlanningDayNotificationJob mails the imminent (day+1) status to; null /
    // blank ⇒ the club opted out and the job skips it. The flag governs the
    // ok-vs-cancel choice when a day has no reservation (see
    // planningDayMailsAsOkWhenNoReservation / shouldSendPlanningDayOk below).
    @Column(name = "send_planning_day_info_mail_to")
    private @Nullable String sendPlanningDayInfoMailTo;

    @Column(name = "use_planning_day_without_reservations", nullable = false)
    private boolean usePlanningDayWithoutReservations;

    // V2 NOT NULL FKs that the walking-skeleton DTO does not surface. Mapped
    // so update operations don't null them; not exposed as setters.
    @Column(name = "country_id", nullable = false)
    private @Nullable UUID countryId;

    @Column(name = "club_state_id", nullable = false)
    private @Nullable UUID clubStateId;

    // FK to the parent Deployment (S-137). Plain UUID, NOT @ManyToOne —
    // keeps the Club aggregate boundary tight; cross-Club iteration goes
    // through DeploymentContext.forEachClub, which sets the tenant per
    // Club rather than fetch-joining the Deployment.
    @Column(name = "deployment_id", nullable = false, updatable = false)
    private @Nullable UUID deploymentId;

    @Column(name = "deleted_on")
    private java.time.@Nullable Instant deletedOn;

    protected Club() {
        // JPA.
    }

    public static Club create(String name, String slug, String clubKey,
                              boolean publicRegistrationEnabled,
                              UUID countryId, UUID clubStateId,
                              UUID deploymentId) {
        if (deploymentId == null) {
            throw new IllegalArgumentException("deploymentId must not be null");
        }
        Club club = new Club();
        club.rename(name);
        club.rebrand(slug);
        club.setClubKey(clubKey);
        club.publicRegistrationEnabled = publicRegistrationEnabled;
        club.countryId = countryId;
        club.clubStateId = clubStateId;
        club.deploymentId = deploymentId;
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

    public void relocate(UUID newCountryId, UUID newClubStateId) {
        if (newCountryId == null) {
            throw new IllegalArgumentException("countryId must not be null");
        }
        if (newClubStateId == null) {
            throw new IllegalArgumentException("clubStateId must not be null");
        }
        this.countryId = newCountryId;
        this.clubStateId = newClubStateId;
    }

    /**
     * Sets the planning-notification recipient address(es). A null or blank
     * value clears the opt-in — the notification job skips clubs without an
     * address (see {@link #wantsPlanningDayNotifications()}). Stored normalized:
     * blank collapses to null.
     */
    public void setPlanningDayInfoMailTo(@Nullable String addresses) {
        this.sendPlanningDayInfoMailTo =
                (addresses == null || addresses.isBlank()) ? null : addresses.strip();
    }

    /** Enables/disables sending the "takes place" mail for reservation-less days. */
    public void setPlanningDayMailsAsOkWhenNoReservation(boolean value) {
        this.usePlanningDayWithoutReservations = value;
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

    public @Nullable UUID getCountryId() {
        return countryId;
    }

    public @Nullable UUID getClubStateId() {
        return clubStateId;
    }

    public @Nullable UUID getDeploymentId() {
        return deploymentId;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    // -- Planning-day notification rule (J-6 T-10b/T-10c, ADR 0022 §2) ----------

    /** The club's planning-notification recipient address(es), or null if opted out. */
    public @Nullable String getPlanningDayInfoMailTo() {
        return sendPlanningDayInfoMailTo;
    }

    /**
     * Whether the club opted into planning-day notifications — i.e. has a
     * non-blank recipient address. The notification job (T-10c) only processes
     * clubs for which this is true (legacy {@code PlanningDayNotificationJob.cs:53}).
     */
    public boolean wantsPlanningDayNotifications() {
        return sendPlanningDayInfoMailTo != null && !sendPlanningDayInfoMailTo.isBlank();
    }

    /**
     * Whether this club still treats a planning day that has NO aircraft
     * reservation as "takes place" (legacy {@code ClubUsePlanningDayWith-
     * outReservations}). Drives the ok-vs-cancel template choice via
     * {@link #shouldSendPlanningDayOk(boolean)}.
     */
    public boolean planningDayMailsAsOkWhenNoReservation() {
        return usePlanningDayWithoutReservations;
    }

    /**
     * The ok-vs-cancel rule the notification job (T-10c) uses for an imminent
     * (day+1) planning day: send the {@code planningday-ok} ("takes place")
     * mail when the day has a reservation OR the club allows reservation-less
     * days; otherwise send {@code planningday-cancel}. Mirrors legacy
     * {@code PlanningDayNotificationJob.cs:75-94} — the rule lives on the
     * aggregate, not the job (ADR 0022 §2).
     *
     * @param hasReservation whether the day has at least one aircraft reservation
     * @return {@code true} ⇒ send {@code planningday-ok}; {@code false} ⇒ {@code planningday-cancel}
     */
    public boolean shouldSendPlanningDayOk(boolean hasReservation) {
        return hasReservation || usePlanningDayWithoutReservations;
    }
}
