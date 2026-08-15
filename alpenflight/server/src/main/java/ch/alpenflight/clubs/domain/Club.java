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

@Entity
@Table(name = "t_club")
public class Club {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,64}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Pattern EMAIL_LIST_SEPARATOR = Pattern.compile("[,;\\s]+");

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CLUB_KEY_LENGTH = 10;
    private static final int MAX_EMAIL_LIST_LENGTH = 250;

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

    @Column(name = "city", length = 100)
    private @Nullable String city;

    @Column(name = "logo_url", length = 500)
    private @Nullable String logoUrl;

    @Column(name = "public_registration_enabled", nullable = false)
    private boolean publicRegistrationEnabled;

    @AuditRedact
    @Column(name = "join_code", nullable = false, length = JoinCodeGenerator.LENGTH)
    private String joinCode = "";

    @Column(name = "send_planning_day_info_mail_to")
    private @Nullable String sendPlanningDayInfoMailTo;

    @Column(name = "use_planning_day_without_reservations", nullable = false)
    private boolean usePlanningDayWithoutReservations;

    @Column(name = "send_trial_flight_registration_operator_email", length = MAX_EMAIL_LIST_LENGTH)
    private @Nullable String discoveryFlightOperatorEmail;

    @Column(name = "send_passenger_flight_registration_operator_email", length = MAX_EMAIL_LIST_LENGTH)
    private @Nullable String scenicFlightOperatorEmail;

    @Column(name = "discovery_flight_type_id")
    private @Nullable UUID discoveryFlightTypeId;

    @Column(name = "homebase_id")
    private @Nullable UUID homebaseId;

    @Column(name = "country_id", nullable = false)
    private @Nullable UUID countryId;

    @Column(name = "club_state_id", nullable = false)
    private @Nullable UUID clubStateId;

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private @Nullable UUID deploymentId;

    @Column(name = "deleted_on")
    private java.time.@Nullable Instant deletedOn;

    protected Club() {
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

    public static boolean isWellFormedSlug(@Nullable String candidate) {
        return candidate != null && SLUG_PATTERN.matcher(candidate).matches();
    }

    public void enablePublicRegistration() {
        this.publicRegistrationEnabled = true;
    }

    public void disablePublicRegistration() {
        this.publicRegistrationEnabled = false;
    }

    public boolean acceptsPublicRegistration() {
        return !isDeleted() && publicRegistrationEnabled;
    }

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

    public void setPlanningDayInfoMailTo(@Nullable String addresses) {
        this.sendPlanningDayInfoMailTo =
                (addresses == null || addresses.isBlank()) ? null : addresses.strip();
    }

    public void setPlanningDayMailsAsOkWhenNoReservation(boolean value) {
        this.usePlanningDayWithoutReservations = value;
    }

    public void setRegistrationOperatorEmails(@Nullable String discoveryFlight,
                                              @Nullable String scenicFlight) {
        String discovery = normalizeEmailList(discoveryFlight, "discoveryFlightOperatorEmail");
        String scenic = normalizeEmailList(scenicFlight, "scenicFlightOperatorEmail");
        this.discoveryFlightOperatorEmail = discovery;
        this.scenicFlightOperatorEmail = scenic;
    }

    public void setDiscoveryFlightType(@Nullable UUID flightTypeId) {
        this.discoveryFlightTypeId = flightTypeId;
    }

    public void relocateHomebase(@Nullable UUID locationId, Predicate<UUID> isOwnActiveLocation) {
        if (locationId != null && !isOwnActiveLocation.test(locationId)) {
            throw new InvalidClubReferenceException("homebaseId");
        }
        this.homebaseId = locationId;
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

    public @Nullable String getCity() {
        return city;
    }

    public @Nullable String getLogoUrl() {
        return logoUrl;
    }

    public boolean isPublicRegistrationEnabled() {
        return publicRegistrationEnabled;
    }

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

    public @Nullable UUID getHomebaseId() {
        return homebaseId;
    }

    public @Nullable String getDiscoveryFlightOperatorEmail() {
        return discoveryFlightOperatorEmail;
    }

    public @Nullable String getScenicFlightOperatorEmail() {
        return scenicFlightOperatorEmail;
    }

    public boolean notifiesDiscoveryFlightOperator() {
        return discoveryFlightOperatorEmail != null && !discoveryFlightOperatorEmail.isBlank();
    }

    public boolean notifiesScenicFlightOperator() {
        return scenicFlightOperatorEmail != null && !scenicFlightOperatorEmail.isBlank();
    }

    public @Nullable UUID getDiscoveryFlightTypeId() {
        return discoveryFlightTypeId;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }


    public @Nullable String getPlanningDayInfoMailTo() {
        return sendPlanningDayInfoMailTo;
    }

    public boolean wantsPlanningDayNotifications() {
        return sendPlanningDayInfoMailTo != null && !sendPlanningDayInfoMailTo.isBlank();
    }

    public boolean planningDayMailsAsOkWhenNoReservation() {
        return usePlanningDayWithoutReservations;
    }

    public boolean shouldSendPlanningDayOk(boolean hasReservation) {
        return hasReservation || usePlanningDayWithoutReservations;
    }
}
