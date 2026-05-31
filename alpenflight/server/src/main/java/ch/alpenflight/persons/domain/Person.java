package ch.alpenflight.persons.domain;

import ch.alpenflight.platform.id.PersonId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Person aggregate root. <strong>Cross-tenant by design</strong> (no
 * {@code @TenantId}) — a single Person row may belong to multiple clubs via
 * the {@link PersonClub} junction. A {@code Flight}'s crew (S-058) loads a
 * Person by PK; the load must succeed even when the Person's tenants don't
 * overlap with the flight's tenant.
 *
 * <p>Per ADR 0022 directive 2, business rules live here on the aggregate
 * and not in the schema:
 *
 * <ul>
 *   <li>{@link #joinClub} — at most one alive {@link PersonClub} per
 *       {@code (person, club)} pair; the partial unique
 *       {@code ux_person_club_alive} is the structural safety net.</li>
 *   <li>Re-join (calling {@code joinClub} for a {@code (person, club)} pair
 *       that has a soft-deleted PersonClub) <strong>reactivates</strong> the
 *       prior row rather than inserting a fresh one — preserves history and
 *       satisfies the partial unique without DELETE-then-INSERT.</li>
 *   <li>{@link #softDelete} refuses when the Person has active PersonClub
 *       rows in tenants other than the caller's; CLUB_ADMIN can only delete
 *       a Person whose only tenant-visible membership is in their own club
 *       (Hibernate's {@code @TenantId} filter scopes the collection
 *       automatically).</li>
 *   <li>Email shape — the two existing {@code ck_person_email_*_shape}
 *       constraints stay as input-shape defense-in-depth; the Email VO at
 *       the application boundary is the authoritative gate.</li>
 * </ul>
 */
@Entity
@Table(name = "t_person")
public class Person {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_ADDRESS_LENGTH = 200;
    private static final int MAX_CITY_LENGTH = 100;
    private static final int MAX_REGION_LENGTH = 100;
    private static final int MAX_ZIP_LENGTH = 10;
    private static final int MAX_PHONE_LENGTH = 30;
    private static final int MAX_EMAIL_LENGTH = 256;
    private static final int MAX_LICENCE_NUMBER_LENGTH = 20;
    private static final int MAX_SPOT_LINK_LENGTH = 250;
    private static final int MAX_COMPANY_NAME_LENGTH = 100;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "lastname", nullable = false, length = MAX_NAME_LENGTH)
    private String lastname = "";

    @Column(name = "firstname", nullable = false, length = MAX_NAME_LENGTH)
    private String firstname = "";

    @Column(name = "midname", length = MAX_NAME_LENGTH)
    private @Nullable String midname;

    @Column(name = "company_name", length = MAX_COMPANY_NAME_LENGTH)
    private @Nullable String companyName;

    @Column(name = "address_line1", length = MAX_ADDRESS_LENGTH)
    private @Nullable String addressLine1;

    @Column(name = "address_line2", length = MAX_ADDRESS_LENGTH)
    private @Nullable String addressLine2;

    @Column(name = "zip", length = MAX_ZIP_LENGTH)
    private @Nullable String zip;

    @Column(name = "city", length = MAX_CITY_LENGTH)
    private @Nullable String city;

    @Column(name = "region", length = MAX_REGION_LENGTH)
    private @Nullable String region;

    @Column(name = "country_id")
    private @Nullable UUID countryId;

    @Column(name = "private_phone", length = MAX_PHONE_LENGTH)
    private @Nullable String privatePhone;

    @Column(name = "mobile_phone", length = MAX_PHONE_LENGTH)
    private @Nullable String mobilePhone;

    @Column(name = "business_phone", length = MAX_PHONE_LENGTH)
    private @Nullable String businessPhone;

    @Column(name = "fax_number", length = MAX_PHONE_LENGTH)
    private @Nullable String faxNumber;

    @Column(name = "email_private", length = MAX_EMAIL_LENGTH)
    private @Nullable String emailPrivate;

    @Column(name = "email_business", length = MAX_EMAIL_LENGTH)
    private @Nullable String emailBusiness;

    @Column(name = "prefer_mail_to_business_mail", nullable = false)
    private boolean preferMailToBusinessMail;

    @Column(name = "birthday")
    private @Nullable LocalDate birthday;

    @Column(name = "has_motor_pilot_licence", nullable = false)
    private boolean hasMotorPilotLicence;

    @Column(name = "has_tow_pilot_licence", nullable = false)
    private boolean hasTowPilotLicence;

    @Column(name = "has_glider_instructor_licence", nullable = false)
    private boolean hasGliderInstructorLicence;

    @Column(name = "has_glider_pilot_licence", nullable = false)
    private boolean hasGliderPilotLicence;

    @Column(name = "has_glider_trainee_licence", nullable = false)
    private boolean hasGliderTraineeLicence;

    @Column(name = "has_glider_pax_licence", nullable = false)
    private boolean hasGliderPaxLicence;

    @Column(name = "has_tmg_licence", nullable = false)
    private boolean hasTmgLicence;

    @Column(name = "has_winch_operator_licence", nullable = false)
    private boolean hasWinchOperatorLicence;

    @Column(name = "has_motor_instructor_licence", nullable = false)
    private boolean hasMotorInstructorLicence;

    @Column(name = "has_part_m_licence", nullable = false)
    private boolean hasPartMLicence;

    @Column(name = "licence_number", length = MAX_LICENCE_NUMBER_LENGTH)
    private @Nullable String licenceNumber;

    @Column(name = "medical_class1_expire_date")
    private @Nullable LocalDate medicalClass1ExpireDate;

    @Column(name = "medical_class2_expire_date")
    private @Nullable LocalDate medicalClass2ExpireDate;

    @Column(name = "medical_lapl_expire_date")
    private @Nullable LocalDate medicalLaplExpireDate;

    @Column(name = "glider_instructor_licence_expire_date")
    private @Nullable LocalDate gliderInstructorLicenceExpireDate;

    @Column(name = "motor_instructor_licence_expire_date")
    private @Nullable LocalDate motorInstructorLicenceExpireDate;

    @Column(name = "part_m_licence_expire_date")
    private @Nullable LocalDate partMLicenceExpireDate;

    @Column(name = "has_glider_towing_start_permission", nullable = false)
    private boolean hasGliderTowingStartPermission;

    @Column(name = "has_glider_self_start_permission", nullable = false)
    private boolean hasGliderSelfStartPermission;

    @Column(name = "has_glider_winch_start_permission", nullable = false)
    private boolean hasGliderWinchStartPermission;

    @Column(name = "spot_link", length = MAX_SPOT_LINK_LENGTH)
    private @Nullable String spotLink;

    @Column(name = "receive_owned_aircraft_statistic_reports", nullable = false)
    private boolean receiveOwnedAircraftStatisticReports;

    @Column(name = "enable_address", nullable = false)
    private boolean enableAddress;

    @Column(name = "is_fast_entry_record", nullable = false)
    private boolean fastEntryRecord;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    @OneToMany(mappedBy = "person",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<PersonClub> personClubs = new ArrayList<>();

    protected Person() {
        // JPA.
    }

    /**
     * Factory for a new Person. Identity-bearing fields only — contact /
     * licence updates go through dedicated mutators.
     */
    public static Person register(String firstname, String lastname, @Nullable String midname) {
        Person p = new Person();
        p.rename(firstname, lastname, midname, null);
        return p;
    }

    public void rename(String newFirstname,
                       String newLastname,
                       @Nullable String newMidname,
                       @Nullable String newCompanyName) {
        this.firstname = requireName(newFirstname, "firstname");
        this.lastname = requireName(newLastname, "lastname");
        this.midname = capLength(blankToNull(newMidname), MAX_NAME_LENGTH, "midname");
        this.companyName = capLength(blankToNull(newCompanyName), MAX_COMPANY_NAME_LENGTH, "companyName");
    }

    public void updateContact(@Nullable String newAddressLine1,
                              @Nullable String newAddressLine2,
                              @Nullable String newZip,
                              @Nullable String newCity,
                              @Nullable String newRegion,
                              @Nullable UUID newCountryId,
                              @Nullable String newPrivatePhone,
                              @Nullable String newMobilePhone,
                              @Nullable String newBusinessPhone,
                              @Nullable String newFaxNumber,
                              @Nullable String newEmailPrivate,
                              @Nullable String newEmailBusiness,
                              boolean newPreferMailToBusinessMail,
                              @Nullable LocalDate newBirthday,
                              @Nullable String newSpotLink,
                              boolean newEnableAddress) {
        this.addressLine1 = capLength(blankToNull(newAddressLine1), MAX_ADDRESS_LENGTH, "addressLine1");
        this.addressLine2 = capLength(blankToNull(newAddressLine2), MAX_ADDRESS_LENGTH, "addressLine2");
        this.zip = capLength(blankToNull(newZip), MAX_ZIP_LENGTH, "zip");
        this.city = capLength(blankToNull(newCity), MAX_CITY_LENGTH, "city");
        this.region = capLength(blankToNull(newRegion), MAX_REGION_LENGTH, "region");
        this.countryId = newCountryId;
        this.privatePhone = capLength(blankToNull(newPrivatePhone), MAX_PHONE_LENGTH, "privatePhone");
        this.mobilePhone = capLength(blankToNull(newMobilePhone), MAX_PHONE_LENGTH, "mobilePhone");
        this.businessPhone = capLength(blankToNull(newBusinessPhone), MAX_PHONE_LENGTH, "businessPhone");
        this.faxNumber = capLength(blankToNull(newFaxNumber), MAX_PHONE_LENGTH, "faxNumber");
        this.emailPrivate = normaliseEmail(newEmailPrivate, "emailPrivate");
        this.emailBusiness = normaliseEmail(newEmailBusiness, "emailBusiness");
        this.preferMailToBusinessMail = newPreferMailToBusinessMail;
        this.birthday = newBirthday;
        this.spotLink = normaliseSpotLink(newSpotLink);
        this.enableAddress = newEnableAddress;
    }

    public void updateLicences(boolean motorPilot,
                               boolean towPilot,
                               boolean gliderInstructor,
                               boolean gliderPilot,
                               boolean gliderTrainee,
                               boolean gliderPax,
                               boolean tmg,
                               boolean winchOperator,
                               boolean motorInstructor,
                               boolean partM,
                               @Nullable String newLicenceNumber,
                               @Nullable LocalDate newMedicalClass1ExpireDate,
                               @Nullable LocalDate newMedicalClass2ExpireDate,
                               @Nullable LocalDate newMedicalLaplExpireDate,
                               @Nullable LocalDate newGliderInstructorLicenceExpireDate,
                               @Nullable LocalDate newMotorInstructorLicenceExpireDate,
                               @Nullable LocalDate newPartMLicenceExpireDate,
                               boolean gliderTowingStartPermission,
                               boolean gliderSelfStartPermission,
                               boolean gliderWinchStartPermission,
                               boolean newReceiveOwnedAircraftStatisticReports) {
        this.hasMotorPilotLicence = motorPilot;
        this.hasTowPilotLicence = towPilot;
        this.hasGliderInstructorLicence = gliderInstructor;
        this.hasGliderPilotLicence = gliderPilot;
        this.hasGliderTraineeLicence = gliderTrainee;
        this.hasGliderPaxLicence = gliderPax;
        this.hasTmgLicence = tmg;
        this.hasWinchOperatorLicence = winchOperator;
        this.hasMotorInstructorLicence = motorInstructor;
        this.hasPartMLicence = partM;
        this.licenceNumber = capLength(blankToNull(newLicenceNumber),
                MAX_LICENCE_NUMBER_LENGTH, "licenceNumber");
        this.medicalClass1ExpireDate = newMedicalClass1ExpireDate;
        this.medicalClass2ExpireDate = newMedicalClass2ExpireDate;
        this.medicalLaplExpireDate = newMedicalLaplExpireDate;
        this.gliderInstructorLicenceExpireDate = newGliderInstructorLicenceExpireDate;
        this.motorInstructorLicenceExpireDate = newMotorInstructorLicenceExpireDate;
        this.partMLicenceExpireDate = newPartMLicenceExpireDate;
        this.hasGliderTowingStartPermission = gliderTowingStartPermission;
        this.hasGliderSelfStartPermission = gliderSelfStartPermission;
        this.hasGliderWinchStartPermission = gliderWinchStartPermission;
        this.receiveOwnedAircraftStatisticReports = newReceiveOwnedAircraftStatisticReports;
    }

    /**
     * Add (or reactivate) a club membership for this Person.
     *
     * <ul>
     *   <li>If an alive {@link PersonClub} already exists for the (person,
     *       club) pair → {@link DuplicateClubMembershipException}.</li>
     *   <li>If a soft-deleted {@link PersonClub} exists for the same pair →
     *       it's reactivated in place (cleared {@code deleted_on}) and its
     *       membership fields replaced.</li>
     *   <li>Otherwise → a fresh {@link PersonClub} is appended to the
     *       collection.</li>
     * </ul>
     *
     * <p>Caller must hold {@code @TenantId} = {@code clubId}; the Hibernate
     * resolver carries this through automatically when the persist flush
     * executes.
     */
    public PersonClub joinClub(UUID clubId,
                               @Nullable String memberNumber,
                               @Nullable UUID memberStateId,
                               PersonRoleFlags roles,
                               PersonNotificationPrefs prefs,
                               boolean active) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId must not be null");
        }
        Optional<PersonClub> alive = aliveMembershipIn(clubId);
        if (alive.isPresent()) {
            throw new DuplicateClubMembershipException(clubId);
        }
        Optional<PersonClub> soft = softDeletedMembershipIn(clubId);
        if (soft.isPresent()) {
            PersonClub pc = soft.get();
            pc.reactivate();
            pc.applyMembership(memberNumber, memberStateId, roles, prefs, active);
            return pc;
        }
        PersonClub pc = PersonClub.open(clubId, memberNumber, memberStateId, roles, prefs, active);
        pc.attachTo(this);
        personClubs.add(pc);
        return pc;
    }

    /**
     * Mutate the alive membership for the caller's tenant. Throws
     * {@link PersonNotFoundException} (404) if the caller has no alive
     * membership for this Person — the {@code @TenantId} filter has already
     * trimmed the collection, so absence here means "not in caller's tenant".
     */
    public PersonClub updateClubMembership(UUID clubId,
                                           @Nullable String memberNumber,
                                           @Nullable UUID memberStateId,
                                           PersonRoleFlags roles,
                                           PersonNotificationPrefs prefs,
                                           boolean active) {
        PersonClub pc = aliveMembershipIn(clubId).orElseThrow(
                () -> new PersonNotFoundException(
                        "Person has no alive PersonClub in club " + clubId));
        pc.applyMembership(memberNumber, memberStateId, roles, prefs, active);
        return pc;
    }

    /** Soft-delete the alive membership for the given club. */
    public void leaveClub(UUID clubId, @Nullable UUID userId, Clock clock) {
        PersonClub pc = aliveMembershipIn(clubId).orElseThrow(
                () -> new PersonNotFoundException(
                        "Person has no alive PersonClub in club " + clubId));
        pc.softDelete(userId, Instant.now(clock));
    }

    /**
     * Soft-delete the Person. CLUB_ADMIN-initiated delete: refused if the
     * caller-tenant-scoped collection has fewer alive memberships than the
     * Person actually has globally — i.e. another tenant still references
     * this Person. The repository's {@code findActiveByIdUnscoped} surfaces
     * the cross-tenant truth; the service calls it before invoking this.
     */
    public void softDelete(@Nullable UUID userId, Clock clock, boolean hasOtherTenantMemberships) {
        if (hasOtherTenantMemberships) {
            throw new CrossTenantMembershipBlockedException(
                    "Person has active club memberships in other tenants — cannot soft-delete from a single tenant");
        }
        if (this.deletedOn == null) {
            Instant now = Instant.now(clock);
            this.deletedOn = now;
            this.deletedByUserId = userId;
            for (PersonClub pc : personClubs) {
                pc.softDelete(userId, now);
            }
        }
    }

    /** Caller-tenant-scoped lookup of an alive PersonClub for the given clubId. */
    private Optional<PersonClub> aliveMembershipIn(UUID clubId) {
        return personClubs.stream()
                .filter(pc -> clubId.equals(pc.getClubId()) && !pc.isDeleted())
                .findFirst();
    }

    private Optional<PersonClub> softDeletedMembershipIn(UUID clubId) {
        return personClubs.stream()
                .filter(pc -> clubId.equals(pc.getClubId()) && pc.isDeleted())
                .findFirst();
    }

    public @Nullable PersonId getId() {
        return PersonId.ofNullable(id);
    }

    public String getLastname() {
        return lastname;
    }

    public String getFirstname() {
        return firstname;
    }

    public @Nullable String getMidname() {
        return midname;
    }

    public @Nullable String getCompanyName() {
        return companyName;
    }

    public @Nullable String getAddressLine1() {
        return addressLine1;
    }

    public @Nullable String getAddressLine2() {
        return addressLine2;
    }

    public @Nullable String getZip() {
        return zip;
    }

    public @Nullable String getCity() {
        return city;
    }

    public @Nullable String getRegion() {
        return region;
    }

    public @Nullable UUID getCountryId() {
        return countryId;
    }

    public @Nullable String getPrivatePhone() {
        return privatePhone;
    }

    public @Nullable String getMobilePhone() {
        return mobilePhone;
    }

    public @Nullable String getBusinessPhone() {
        return businessPhone;
    }

    public @Nullable String getFaxNumber() {
        return faxNumber;
    }

    public @Nullable String getEmailPrivate() {
        return emailPrivate;
    }

    public @Nullable String getEmailBusiness() {
        return emailBusiness;
    }

    public boolean isPreferMailToBusinessMail() {
        return preferMailToBusinessMail;
    }

    public @Nullable LocalDate getBirthday() {
        return birthday;
    }

    public boolean hasMotorPilotLicence() {
        return hasMotorPilotLicence;
    }

    public boolean hasTowPilotLicence() {
        return hasTowPilotLicence;
    }

    public boolean hasGliderInstructorLicence() {
        return hasGliderInstructorLicence;
    }

    public boolean hasGliderPilotLicence() {
        return hasGliderPilotLicence;
    }

    public boolean hasGliderTraineeLicence() {
        return hasGliderTraineeLicence;
    }

    public boolean hasGliderPaxLicence() {
        return hasGliderPaxLicence;
    }

    public boolean hasTmgLicence() {
        return hasTmgLicence;
    }

    public boolean hasWinchOperatorLicence() {
        return hasWinchOperatorLicence;
    }

    public boolean hasMotorInstructorLicence() {
        return hasMotorInstructorLicence;
    }

    public boolean hasPartMLicence() {
        return hasPartMLicence;
    }

    public @Nullable String getLicenceNumber() {
        return licenceNumber;
    }

    public @Nullable LocalDate getMedicalClass1ExpireDate() {
        return medicalClass1ExpireDate;
    }

    public @Nullable LocalDate getMedicalClass2ExpireDate() {
        return medicalClass2ExpireDate;
    }

    public @Nullable LocalDate getMedicalLaplExpireDate() {
        return medicalLaplExpireDate;
    }

    public @Nullable LocalDate getGliderInstructorLicenceExpireDate() {
        return gliderInstructorLicenceExpireDate;
    }

    public @Nullable LocalDate getMotorInstructorLicenceExpireDate() {
        return motorInstructorLicenceExpireDate;
    }

    public @Nullable LocalDate getPartMLicenceExpireDate() {
        return partMLicenceExpireDate;
    }

    public boolean hasGliderTowingStartPermission() {
        return hasGliderTowingStartPermission;
    }

    public boolean hasGliderSelfStartPermission() {
        return hasGliderSelfStartPermission;
    }

    public boolean hasGliderWinchStartPermission() {
        return hasGliderWinchStartPermission;
    }

    public @Nullable String getSpotLink() {
        return spotLink;
    }

    public boolean isReceiveOwnedAircraftStatisticReports() {
        return receiveOwnedAircraftStatisticReports;
    }

    public boolean isEnableAddress() {
        return enableAddress;
    }

    public boolean isFastEntryRecord() {
        return fastEntryRecord;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    public List<PersonClub> getPersonClubs() {
        return Collections.unmodifiableList(personClubs);
    }

    /** Caller-tenant-visible alive memberships (Hibernate has scoped the collection). */
    public List<PersonClub> getActivePersonClubs() {
        return personClubs.stream().filter(pc -> !pc.isDeleted()).toList();
    }

    private static String requireName(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static @Nullable String capLength(@Nullable String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return value;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable String normaliseEmail(@Nullable String value, String field) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(lower).matches()) {
            throw new IllegalArgumentException(field + " is not a valid email: " + value);
        }
        if (lower.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_EMAIL_LENGTH + " characters");
        }
        return lower;
    }

    private static @Nullable String normaliseSpotLink(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > MAX_SPOT_LINK_LENGTH) {
            throw new IllegalArgumentException("spotLink exceeds " + MAX_SPOT_LINK_LENGTH + " characters");
        }
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException(
                    "spotLink must start with 'https://' (A10 SSRF defense-in-depth), got: " + value);
        }
        return trimmed;
    }
}
