package ch.alpenflight.persons.application;

import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the Persons REST surface. Records (immutable; explicit field
 * set); mass-assignment is structurally impossible because the controller
 * binds to the record, not to the {@link ch.alpenflight.persons.domain.Person}
 * aggregate.
 *
 * <p>The {@code PersonResponse} shape carries the caller-tenant's
 * memberships only (Hibernate {@code @TenantId} on
 * {@link ch.alpenflight.persons.domain.PersonClub} has already scoped the
 * collection) plus an opaque {@code inOtherClubsCount} integer — no other-
 * club names. SYSTEM_ADMINISTRATOR's cross-cutting view (if and when
 * shipped under {@code /api/v1/admin/persons}) would replace the
 * {@code memberships} list with an exhaustive one.
 *
 * <p>{@code PersonClubRequest} sub-resource fields ({@code memberNumber},
 * {@code memberStateId}, role flags, notification prefs) are also reachable
 * via the inline {@code initialClubMembership} block on
 * {@link PersonCreateRequest} for the one-tx create flow.
 */
public final class PersonDtos {

    private PersonDtos() {}

    static final String SPOT_LINK_REGEX = "^https://.*";

    @Schema(description = "Caller-visible PersonClub row (the caller's tenant only, per Hibernate's @TenantId filter).")
    public record PersonClubResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID clubId,
            @Nullable String memberNumber,
            @Nullable UUID memberStateId,
            @Nullable String memberStateName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isMotorPilot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowPilot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isGliderInstructor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isGliderPilot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isGliderTrainee,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isPassenger,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isWinchOperator,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isMotorInstructor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean receiveFlightReports,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean receiveAircraftReservationNotifications,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean receivePlanningDayRoleReminder,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isActive) {}

    @Schema(description = "Person listitem projection — one row per (Person, caller-tenant PersonClub) pair.")
    public record PersonListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PersonId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String firstname,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastname,
            @Nullable String email,
            @Nullable String mobilePhone,
            @Nullable String city,
            @Nullable String zip,
            @Nullable String memberNumber,
            @Nullable UUID memberStateId,
            @Nullable String memberStateName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isActive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isMotorPilot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowPilot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isGliderInstructor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isGliderPilot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isGliderTrainee,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isWinchOperator,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isMotorInstructor) {}

    @Schema(description = "Person detail projection — Person identity / contact / licences + caller-visible memberships.")
    public record PersonResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PersonId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String firstname,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastname,
            @Nullable String midname,
            @Nullable String companyName,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String zip,
            @Nullable String city,
            @Nullable String region,
            @Nullable UUID countryId,
            @Nullable String privatePhone,
            @Nullable String mobilePhone,
            @Nullable String businessPhone,
            @Nullable String faxNumber,
            @Nullable String emailPrivate,
            @Nullable String emailBusiness,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean preferMailToBusinessMail,
            @Nullable LocalDate birthday,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMotorPilotLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasTowPilotLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderInstructorLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderPilotLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderTraineeLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderPaxLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasTmgLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasWinchOperatorLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMotorInstructorLicence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasPartMLicence,
            @Nullable String licenceNumber,
            @Nullable LocalDate medicalClass1ExpireDate,
            @Nullable LocalDate medicalClass2ExpireDate,
            @Nullable LocalDate medicalLaplExpireDate,
            @Nullable LocalDate gliderInstructorLicenceExpireDate,
            @Nullable LocalDate motorInstructorLicenceExpireDate,
            @Nullable LocalDate partMLicenceExpireDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderTowingStartPermission,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderSelfStartPermission,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasGliderWinchStartPermission,
            @Nullable String spotLink,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean receiveOwnedAircraftStatisticReports,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enableAddress,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PersonClubResponse> memberships,
            @Schema(description = "Opaque count of memberships in clubs the caller cannot see. 0 for sysadmin cross-cutting reads.",
                    requiredMode = Schema.RequiredMode.REQUIRED) int inOtherClubsCount) {}

    @Schema(description = "Per-club membership shape. Used as nested in the create request and as request body for join/update sub-resources.")
    public record PersonClubRequest(
            @Nullable @Size(max = 20) String memberNumber,
            @Nullable UUID memberStateId,
            boolean isMotorPilot,
            boolean isTowPilot,
            boolean isGliderInstructor,
            boolean isGliderPilot,
            boolean isGliderTrainee,
            boolean isPassenger,
            boolean isWinchOperator,
            boolean isMotorInstructor,
            boolean receiveFlightReports,
            boolean receiveAircraftReservationNotifications,
            boolean receivePlanningDayRoleReminder,
            boolean isActive) {}

    @Schema(description = "Payload to create a new Person. The optional initialClubMembership creates the first PersonClub in the caller's tenant in the same transaction.")
    public record PersonCreateRequest(
            @NotBlank @Size(max = 100) String firstname,
            @NotBlank @Size(max = 100) String lastname,
            @Nullable @Size(max = 100) String midname,
            @Nullable @Size(max = 100) String companyName,
            @Nullable @Size(max = 200) String addressLine1,
            @Nullable @Size(max = 200) String addressLine2,
            @Nullable @Size(max = 10) String zip,
            @Nullable @Size(max = 100) String city,
            @Nullable @Size(max = 100) String region,
            @Nullable UUID countryId,
            @Nullable @Size(max = 30) String privatePhone,
            @Nullable @Size(max = 30) String mobilePhone,
            @Nullable @Size(max = 30) String businessPhone,
            @Nullable @Size(max = 30) String faxNumber,
            @Nullable @Email @Size(max = 256) String emailPrivate,
            @Nullable @Email @Size(max = 256) String emailBusiness,
            boolean preferMailToBusinessMail,
            @Nullable LocalDate birthday,
            @Valid @Nullable LicenceFlags licences,
            @Valid @Nullable PersonClubRequest initialClubMembership,
            @Nullable @Size(max = 250)
                    @Pattern(regexp = SPOT_LINK_REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                            message = "spotLink must start with https:// (A10 SSRF defense-in-depth)")
                    String spotLink,
            boolean receiveOwnedAircraftStatisticReports,
            boolean enableAddress) {}

    @Schema(description = "Payload to update Person identity + contact + licences. PersonClub mutations go through /api/v1/persons/{id}/clubs/current.")
    public record PersonUpdateRequest(
            @NotBlank @Size(max = 100) String firstname,
            @NotBlank @Size(max = 100) String lastname,
            @Nullable @Size(max = 100) String midname,
            @Nullable @Size(max = 100) String companyName,
            @Nullable @Size(max = 200) String addressLine1,
            @Nullable @Size(max = 200) String addressLine2,
            @Nullable @Size(max = 10) String zip,
            @Nullable @Size(max = 100) String city,
            @Nullable @Size(max = 100) String region,
            @Nullable UUID countryId,
            @Nullable @Size(max = 30) String privatePhone,
            @Nullable @Size(max = 30) String mobilePhone,
            @Nullable @Size(max = 30) String businessPhone,
            @Nullable @Size(max = 30) String faxNumber,
            @Nullable @Email @Size(max = 256) String emailPrivate,
            @Nullable @Email @Size(max = 256) String emailBusiness,
            boolean preferMailToBusinessMail,
            @Nullable LocalDate birthday,
            @Valid @Nullable LicenceFlags licences,
            @Nullable @Size(max = 250)
                    @Pattern(regexp = SPOT_LINK_REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                            message = "spotLink must start with https:// (A10 SSRF defense-in-depth)")
                    String spotLink,
            boolean receiveOwnedAircraftStatisticReports,
            boolean enableAddress) {}

    @Schema(description = "Person-level licence flags + expiry dates. Nullable on Create/Update — omit to keep current values.")
    public record LicenceFlags(
            boolean hasMotorPilotLicence,
            boolean hasTowPilotLicence,
            boolean hasGliderInstructorLicence,
            boolean hasGliderPilotLicence,
            boolean hasGliderTraineeLicence,
            boolean hasGliderPaxLicence,
            boolean hasTmgLicence,
            boolean hasWinchOperatorLicence,
            boolean hasMotorInstructorLicence,
            boolean hasPartMLicence,
            @Nullable @Size(max = 20) String licenceNumber,
            @Nullable LocalDate medicalClass1ExpireDate,
            @Nullable LocalDate medicalClass2ExpireDate,
            @Nullable LocalDate medicalLaplExpireDate,
            @Nullable LocalDate gliderInstructorLicenceExpireDate,
            @Nullable LocalDate motorInstructorLicenceExpireDate,
            @Nullable LocalDate partMLicenceExpireDate,
            boolean hasGliderTowingStartPermission,
            boolean hasGliderSelfStartPermission,
            boolean hasGliderWinchStartPermission) {}

    /**
     * Exact-match-only lookup payload. Per S-051 security plan (row 1)
     * prefix search is structurally banned: a bare {@code q=} parameter
     * doesn't exist on the schema. The {@code isExactMatchShape} cross-field
     * validator rejects partial triples — caller must provide {@code email}
     * OR the full identity triple (firstname + lastname + birthday).
     */
    @Schema(description = "Exact-match-only lookup payload. Either provide email, OR provide the full identity triple (firstname + lastname + birthday). Partial triples are rejected.")
    public record PersonLookupRequest(
            @Nullable @Email @Size(max = 256) String email,
            @Nullable @Size(max = 100) String firstname,
            @Nullable @Size(max = 100) String lastname,
            @Nullable LocalDate birthday) {

        @AssertTrue(message = "Provide email, OR all three of (firstname, lastname, birthday). Partial triples are rejected.")
        public boolean isExactMatchShape() {
            boolean hasEmail = email != null && !email.isBlank();
            boolean hasFullTriple = firstname != null && !firstname.isBlank()
                    && lastname != null && !lastname.isBlank()
                    && birthday != null;
            boolean hasNoTripleField = (firstname == null || firstname.isBlank())
                    && (lastname == null || lastname.isBlank())
                    && birthday == null;
            return (hasEmail && hasNoTripleField) || (hasFullTriple && !hasEmail);
        }
    }

    @Schema(description = "Cap at 5; exact-match never returns more.")
    public record PersonLookupResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PersonLookupMatch> matches) {}

    @Schema(description = "Slim per-match shape. No address, no licence flags — just enough to identify the person before attaching.")
    public record PersonLookupMatch(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PersonId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String firstname,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastname,
            @Nullable LocalDate birthday,
            @Nullable String email,
            @Schema(description = "True iff this Person already has an alive PersonClub in the caller's tenant.",
                    requiredMode = Schema.RequiredMode.REQUIRED) boolean alreadyInThisClub) {}

    @Schema(description = "Tenant-scoped listitem projection for the form picker.")
    public record MemberStateListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}
}
