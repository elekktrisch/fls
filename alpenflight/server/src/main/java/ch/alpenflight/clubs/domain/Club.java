package ch.alpenflight.clubs.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.platform.id.ClubId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * etc.) are intentionally NOT mapped on this aggregate today — the DTO surface is
 * deliberately narrow. Stories that need to read/write those columns extend
 * the entity, as {@code city} + {@code logo_url} are mapped here for the pilot
 * join-pending public display.
 */
@Entity
@Table(name = "t_club")
public class Club {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,64}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // Legacy accepts comma / semicolon / whitespace as separators in every
    // multi-recipient club address (StringExtensions.FormatMultipleEmailAddresses)
    // and normalizes to a comma-joined string before handing it to the mailer.
    private static final Pattern EMAIL_LIST_SEPARATOR = Pattern.compile("[,;\\s]+");

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CLUB_KEY_LENGTH = 10;
    private static final int MAX_EMAIL_LIST_LENGTH = 250;

    // Bounds the global-uniqueness retry. At 40-bit entropy a collision against
    // ~dozens of clubs is astronomically rare, so a handful of attempts proves
    // a wiring fault (e.g. a generator stuck on one value) rather than churning.
    private static final int MAX_JOIN_CODE_ATTEMPTS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "clubname", nullable = false, length = MAX_NAME_LENGTH)
    private String clubname = "";

    @Column(name = "club_key", nullable = false, length = MAX_CLUB_KEY_LENGTH)
    private String clubKey = "";

    @Column(name = "slug", length = 64)
    private @Nullable String slug;

    // Public-display fields shown on the pilot's tenant-less /join/pending page.
    // `logoUrl` is net-new with no legacy source — null until a club sets one.
    @Column(name = "city", length = 100)
    private @Nullable String city;

    @Column(name = "logo_url", length = 500)
    private @Nullable String logoUrl;

    @Column(name = "public_registration_enabled", nullable = false)
    private boolean publicRegistrationEnabled;

    // The rotatable, club-shared discovery code a pilot types to file a join
    // request. A quasi-secret, not an auth token — admission is the
    // admin's approval. Global UNIQUE (ux_club_join_code) so one code resolves
    // to exactly one Club; @AuditRedact keeps it out of the audit ledger.
    @AuditRedact
    @Column(name = "join_code", nullable = false, length = JoinCodeGenerator.LENGTH)
    private String joinCode = "";

    // The address the PlanningDayNotificationJob mails the imminent (day+1) status to; null /
    // blank ⇒ the club opted out and the job skips it. The flag governs the
    // ok-vs-cancel choice when a day has no reservation (see
    // planningDayMailsAsOkWhenNoReservation / shouldSendPlanningDayOk below).
    @Column(name = "send_planning_day_info_mail_to")
    private @Nullable String sendPlanningDayInfoMailTo;

    @Column(name = "use_planning_day_without_reservations", nullable = false)
    private boolean usePlanningDayWithoutReservations;

    // Organiser-notification recipients for the two public flight-experience
    // registration flows. Distinct from "the club admin" and independently
    // optional: with no address legacy logs a warning and sends no organiser
    // mail, so the registration itself still succeeds.
    @Column(name = "send_trial_flight_registration_operator_email", length = MAX_EMAIL_LIST_LENGTH)
    private @Nullable String discoveryFlightOperatorEmail;

    @Column(name = "send_passenger_flight_registration_operator_email", length = MAX_EMAIL_LIST_LENGTH)
    private @Nullable String scenicFlightOperatorEmail;

    // Flight type stamped on the reservation a discovery-flight registration
    // books. Nullable by design — an unset (or since-deleted) type books the
    // reservation without one rather than failing the registration.
    @Column(name = "discovery_flight_type_id")
    private @Nullable UUID discoveryFlightTypeId;

    // Homebase Location FK (fk_club_homebase_id, ON DELETE SET NULL).
    // Read-only on this aggregate today: surfaced through GET /api/v1/me
    // (MeService → MeView.homebaseLocationId) so the SPA can scope LOCATION
    // canned reports to the club's homebase. No write path exists yet.
    @Column(name = "homebase_id")
    private @Nullable UUID homebaseId;

    // NOT NULL FKs the DTO does not surface. Mapped
    // so update operations don't null them; not exposed as setters.
    @Column(name = "country_id", nullable = false)
    private @Nullable UUID countryId;

    @Column(name = "club_state_id", nullable = false)
    private @Nullable UUID clubStateId;

    // FK to the parent Deployment. Plain UUID, NOT @ManyToOne —
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
        // Every club has a join code from birth. The unique index is the
        // global-uniqueness backstop at creation; rotation later re-mints with an
        // explicit collision-retry.
        club.joinCode = JoinCodeGenerator.secureRandom().generate();
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
        if (!isWellFormedSlug(newSlug)) {
            throw new IllegalArgumentException(
                    "Slug must match ^[a-z0-9-]{3,64}$, got: " + newSlug);
        }
        this.slug = newSlug;
    }

    /**
     * Whether {@code candidate} is shaped like a club slug at all. Public so the
     * anonymous slug→club resolution can reject a malformed path segment without
     * a database round-trip — an unauthenticated surface should not turn arbitrary
     * URL text into a query.
     */
    public static boolean isWellFormedSlug(@Nullable String candidate) {
        return candidate != null && SLUG_PATTERN.matcher(candidate).matches();
    }

    public void enablePublicRegistration() {
        this.publicRegistrationEnabled = true;
    }

    public void disablePublicRegistration() {
        this.publicRegistrationEnabled = false;
    }

    /**
     * Whether this club is a legitimate target for an anonymous public-registration
     * submission (S-025 allowlist). Both conditions are the club's own state, so
     * the rule lives here rather than in the resolver (ADR 0022 directive 2): a
     * soft-deleted club admits nobody, and the flag is the club's explicit opt-in.
     */
    public boolean acceptsPublicRegistration() {
        return !isDeleted() && publicRegistrationEnabled;
    }

    /**
     * Replaces this club's join code with a freshly minted one. Rotation is
     * always allowed (no domain rate-limit — the cost is small and the audit
     * row suffices). Pending requests filed under the old code stay
     * valid; the rotation only invalidates the old code as a discovery key.
     *
     * <p>The new code must be globally unique ({@code ux_club_join_code}). The
     * retry loop lives here, not in the service, because uniqueness is part of
     * the rotation rule (ADR 0022 §2): {@code isUnique} answers whether a
     * candidate is free across all clubs, and the loop redraws on the rare
     * collision until it finds a free code or exhausts {@value #MAX_JOIN_CODE_ATTEMPTS}.
     *
     * @param generator mints a candidate code
     * @param isUnique  true iff the candidate is free across every Club
     * @return the new code (also stored on the aggregate)
     */
    public String rotateJoinCode(JoinCodeGenerator generator, Predicate<String> isUnique) {
        for (int attempt = 0; attempt < MAX_JOIN_CODE_ATTEMPTS; attempt++) {
            String candidate = generator.generate();
            if (isUnique.test(candidate)) {
                this.joinCode = candidate;
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not mint a unique join code in %d attempts"
                        .formatted(MAX_JOIN_CODE_ATTEMPTS));
    }

    /**
     * Sets the public-display fields shown to a pilot before they belong to the
     * club. Both normalize blank to null so an empty input clears the
     * field rather than storing whitespace.
     */
    public void setPublicDisplay(@Nullable String city, @Nullable String logoUrl) {
        this.city = blankToNull(city);
        this.logoUrl = blankToNull(logoUrl);
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

    /**
     * Sets the organiser-notification recipients for the two public
     * flight-experience registration flows.
     *
     * <p>Each value is a recipient <em>list</em>: addresses separated by comma,
     * semicolon or whitespace, stored canonically as a lowercase comma-joined
     * string. Null or blank clears the opt-in and is a valid state — the flow
     * then simply skips the organiser mail. Every listed address must parse, so
     * a typo is rejected at the write boundary rather than silently swallowed at
     * send time.
     *
     * <p>Both values are validated before either is applied, so a rejected value
     * never leaves the aggregate half-updated.
     */
    public void setRegistrationOperatorEmails(@Nullable String discoveryFlight,
                                              @Nullable String scenicFlight) {
        String discovery = normalizeEmailList(discoveryFlight, "discoveryFlightOperatorEmail");
        String scenic = normalizeEmailList(scenicFlight, "scenicFlightOperatorEmail");
        this.discoveryFlightOperatorEmail = discovery;
        this.scenicFlightOperatorEmail = scenic;
    }

    /** Sets (or clears, with null) the flight type a discovery reservation is booked under. */
    public void setDiscoveryFlightType(@Nullable UUID flightTypeId) {
        this.discoveryFlightTypeId = flightTypeId;
    }

    public void softDelete(Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
        }
    }

    private static @Nullable String normalizeEmailList(@Nullable String value, String field) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        String canonical = EMAIL_LIST_SEPARATOR.splitAsStream(trimmed)
                .filter(address -> !address.isEmpty())
                .map(address -> validEmailAddress(address, field))
                .collect(Collectors.joining(","));
        if (canonical.isEmpty()) {
            return null;
        }
        if (canonical.length() > MAX_EMAIL_LIST_LENGTH) {
            throw new IllegalArgumentException(
                    "%s exceeds %d characters".formatted(field, MAX_EMAIL_LIST_LENGTH));
        }
        return canonical;
    }

    private static String validEmailAddress(String address, String field) {
        String lower = address.toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(lower).matches()) {
            throw new IllegalArgumentException(
                    "%s contains an invalid email address: %s".formatted(field, address));
        }
        return lower;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
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

    /** The club's city, shown on the pilot join-pending public display. */
    public @Nullable String getCity() {
        return city;
    }

    /** The club's logo URL, or null when none is set (net-new, no legacy source). */
    public @Nullable String getLogoUrl() {
        return logoUrl;
    }

    public boolean isPublicRegistrationEnabled() {
        return publicRegistrationEnabled;
    }

    /** The club's current join code — admin-only on the wire. */
    public String getJoinCode() {
        return joinCode;
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

    /** The club's homebase Location id, or null when no homebase is set. */
    public @Nullable UUID getHomebaseId() {
        return homebaseId;
    }

    /** Comma-joined organiser recipients for discovery-flight registrations, or null. */
    public @Nullable String getDiscoveryFlightOperatorEmail() {
        return discoveryFlightOperatorEmail;
    }

    /** Comma-joined organiser recipients for scenic-flight registrations, or null. */
    public @Nullable String getScenicFlightOperatorEmail() {
        return scenicFlightOperatorEmail;
    }

    /**
     * Whether a discovery-flight registration should mail the organiser.
     * Tolerates a blank value because migrated rows carry legacy's empty
     * strings, which mean the same thing as an absent address.
     */
    public boolean notifiesDiscoveryFlightOperator() {
        return discoveryFlightOperatorEmail != null && !discoveryFlightOperatorEmail.isBlank();
    }

    /** Whether a scenic-flight registration should mail the organiser. */
    public boolean notifiesScenicFlightOperator() {
        return scenicFlightOperatorEmail != null && !scenicFlightOperatorEmail.isBlank();
    }

    /** The flight type a discovery reservation is booked under, or null when unset. */
    public @Nullable UUID getDiscoveryFlightTypeId() {
        return discoveryFlightTypeId;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    // -- Planning-day notification rule (ADR 0022 §2) --------------------------

    /** The club's planning-notification recipient address(es), or null if opted out. */
    public @Nullable String getPlanningDayInfoMailTo() {
        return sendPlanningDayInfoMailTo;
    }

    /**
     * Whether the club opted into planning-day notifications — i.e. has a
     * non-blank recipient address. The notification job only processes
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
     * The ok-vs-cancel rule the notification job uses for an imminent
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
