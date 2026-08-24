package ch.alpenflight.persons.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.persons.application.PersonDtos.PersonClubRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonClubResponse;
import ch.alpenflight.persons.application.PersonDtos.PersonCreateRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonListItem;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupMatch;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupResult;
import ch.alpenflight.persons.application.PersonDtos.PersonResponse;
import ch.alpenflight.persons.application.PersonDtos.PersonUpdateRequest;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonNotFoundException;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PersonsService {

    private static final String AUDIT_PERSON = "Person";
    private static final String AUDIT_PERSON_CLUB = "PersonClub";
    private static final String AUDIT_PERSON_LICENCES = "PersonLicences";
    private static final String AUDIT_PERSON_CLUB_NOTIFICATION_PREFS = "PersonClubNotificationPrefs";
    private static final int LOOKUP_RESULT_CAP = 5;

    private final PersonRepository persons;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final MemberStateSlice memberStates;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public PersonsService(PersonRepository persons,
                          ClubTenantIdentifierResolver tenantResolver,
                          MemberStateSlice memberStates,
                          AuditTrail auditTrail,
                          Clock clock) {
        this.persons = persons;
        this.tenantResolver = tenantResolver;
        this.memberStates = memberStates;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PersonListItem> listInCurrentTenant() {
        return persons.findActiveListRowsInCurrentTenant().stream()
                .map(PersonMapper::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public PersonResponse getPerson(PersonId id) {
        Person p = loadInCurrentTenantOrThrow(id);
        return toResponse(p);
    }

    public PersonResponse createPerson(PersonCreateRequest req) {
        Person p = Person.register(req.firstname(), req.lastname(), req.midname());
        p.rename(req.firstname(), req.lastname(), req.midname(), req.companyName());
        p.updateContact(
                req.addressLine1(), req.addressLine2(), req.zip(), req.city(), req.region(),
                req.countryId(),
                req.privatePhone(), req.mobilePhone(), req.businessPhone(), req.faxNumber(),
                req.emailPrivate(), req.emailBusiness(), req.preferMailToBusinessMail(),
                req.birthday(), req.spotLink(), req.enableAddress());
        PersonMapper.applyLicences(p, req.licences());

        UUID tenant = currentTenantOrThrow();
        if (req.initialClubMembership() != null) {
            applyJoin(p, tenant, req.initialClubMembership());
        }
        Person saved = persistPerson(p);
        PersonResponse after = toResponse(saved);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_PERSON, after.id().value(), saved));
        return after;
    }

    public PersonResponse updatePerson(PersonId id, PersonUpdateRequest req) {
        Person p = loadInCurrentTenantOrThrow(id);
        PersonResponse beforeSnapshot = toResponse(p);
        p.rename(req.firstname(), req.lastname(), req.midname(), req.companyName());
        p.updateContact(
                req.addressLine1(), req.addressLine2(), req.zip(), req.city(), req.region(),
                req.countryId(),
                req.privatePhone(), req.mobilePhone(), req.businessPhone(), req.faxNumber(),
                req.emailPrivate(), req.emailBusiness(), req.preferMailToBusinessMail(),
                req.birthday(), req.spotLink(), req.enableAddress());
        PersonMapper.applyLicences(p, req.licences());
        Person saved = persistPerson(p);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_PERSON, id.value(), beforeSnapshot, saved));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public SelfContactView getOwnContact(UUID callerOwnPersonId) {
        Person p = persons.findActiveById(callerOwnPersonId)
                .orElseThrow(() -> new PersonNotFoundException(PersonId.of(callerOwnPersonId)));
        return SelfContactView.of(p);
    }

    public void updateOwnContact(UUID callerOwnPersonId, SelfContactUpdate cmd) {
        Person p = persons.findActiveById(callerOwnPersonId)
                .orElseThrow(() -> new PersonNotFoundException(PersonId.of(callerOwnPersonId)));
        ContactSnapshot beforeSnapshot = ContactSnapshot.of(p);
        p.updateContact(
                cmd.addressLine1(), cmd.addressLine2(), cmd.zip(), cmd.city(), cmd.region(),
                cmd.countryId(),
                cmd.privatePhone(), cmd.mobilePhone(), cmd.businessPhone(), cmd.faxNumber(),
                cmd.emailPrivate(), cmd.emailBusiness(), cmd.preferMailToBusinessMail(),
                cmd.birthday(),
                p.getSpotLink(), p.isEnableAddress());
        Person saved = persistPerson(p);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_PERSON, callerOwnPersonId, beforeSnapshot,
                        ContactSnapshot.of(saved)));
    }

    private record ContactSnapshot(
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
            boolean preferMailToBusinessMail,
            @Nullable LocalDate birthday) {

        static ContactSnapshot of(Person p) {
            return new ContactSnapshot(
                    p.getAddressLine1(), p.getAddressLine2(), p.getZip(), p.getCity(), p.getRegion(),
                    p.getCountryId(),
                    p.getPrivatePhone(), p.getMobilePhone(), p.getBusinessPhone(), p.getFaxNumber(),
                    p.getEmailPrivate(), p.getEmailBusiness(), p.isPreferMailToBusinessMail(),
                    p.getBirthday());
        }
    }

    @Transactional(readOnly = true)
    public SelfLicencesView getOwnLicences(UUID callerOwnPersonId) {
        Person p = persons.findActiveById(callerOwnPersonId)
                .orElseThrow(() -> new PersonNotFoundException(PersonId.of(callerOwnPersonId)));
        return SelfLicencesView.of(p);
    }

    public void updateOwnLicences(UUID callerOwnPersonId, SelfLicencesUpdate cmd) {
        Person p = persons.findActiveById(callerOwnPersonId)
                .orElseThrow(() -> new PersonNotFoundException(PersonId.of(callerOwnPersonId)));
        SelfLicencesView beforeSnapshot = SelfLicencesView.of(p);
        p.updateLicences(
                cmd.motorPilot(), cmd.towPilot(), cmd.gliderInstructor(), cmd.gliderPilot(),
                cmd.gliderTrainee(), cmd.gliderPax(), cmd.tmg(), cmd.winchOperator(),
                cmd.motorInstructor(), cmd.partM(),
                cmd.licenceNumber(),
                cmd.medicalClass1ExpireDate(), cmd.medicalClass2ExpireDate(),
                cmd.medicalLaplExpireDate(),
                cmd.gliderInstructorLicenceExpireDate(), cmd.motorInstructorLicenceExpireDate(),
                cmd.partMLicenceExpireDate(),
                cmd.gliderTowingStartPermission(), cmd.gliderSelfStartPermission(),
                cmd.gliderWinchStartPermission(),
                cmd.receiveOwnedAircraftStatisticReports());
        Person saved = persistPerson(p);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_PERSON_LICENCES, callerOwnPersonId, beforeSnapshot,
                        SelfLicencesView.of(saved)));
    }

    @Transactional(readOnly = true)
    public SelfNotificationPrefsView getOwnNotificationPrefs(UUID callerOwnPersonId,
                                                            UUID callerTenantClubId) {
        Person p = persons.findActiveById(callerOwnPersonId)
                .orElseThrow(() -> new PersonNotFoundException(PersonId.of(callerOwnPersonId)));
        PersonClub pc = aliveMembershipInOrThrow(p, callerTenantClubId);
        return SelfNotificationPrefsView.of(pc);
    }

    public void updateOwnNotificationPrefs(UUID callerOwnPersonId, UUID callerTenantClubId,
                                           SelfNotificationPrefsUpdate cmd) {
        Person p = persons.findActiveById(callerOwnPersonId)
                .orElseThrow(() -> new PersonNotFoundException(PersonId.of(callerOwnPersonId)));
        PersonClub membershipBeforeUpdate = aliveMembershipInOrThrow(p, callerTenantClubId);
        SelfNotificationPrefsView beforeSnapshot =
                SelfNotificationPrefsView.of(membershipBeforeUpdate);
        PersonClub pc = p.updateNotificationPrefs(callerTenantClubId, new PersonNotificationPrefs(
                cmd.receiveFlightReports(),
                cmd.receiveAircraftReservationNotifications(),
                cmd.receivePlanningDayRoleReminder()));
        persistPerson(p);
        UUID pcId = requirePersonClubId(pc);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_PERSON_CLUB_NOTIFICATION_PREFS, pcId,
                        beforeSnapshot, SelfNotificationPrefsView.of(pc)));
    }

    private static PersonClub aliveMembershipInOrThrow(Person p, UUID clubId) {
        return p.getActivePersonClubs().stream()
                .filter(pc -> clubId.equals(pc.getClubId()))
                .findFirst()
                .orElseThrow(() -> new PersonNotFoundException(
                        "Person has no alive PersonClub in club " + clubId));
    }

    public void softDeletePerson(PersonId id, @Nullable UUID userId) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        boolean inOtherTenant = persons.hasActiveMembershipInOtherTenant(id.value(), tenant);
        PersonResponse beforeSnapshot = toResponse(p);
        p.softDelete(userId, clock, inOtherTenant);
        persistPerson(p);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_PERSON, id.value(), beforeSnapshot));
    }

    public PersonResponse attachExistingPerson(PersonId id, PersonClubRequest req) {
        UUID tenant = currentTenantOrThrow();
        Person p = persons.findActiveByIdInSameDeploymentAs(id.value(), tenant)
                .orElseThrow(() -> new PersonNotFoundException(id));
        applyJoin(p, tenant, req);
        Person saved = persistPerson(p);
        PersonClub attached = aliveMembershipInOrThrow(saved, tenant);
        UUID pcId = requirePersonClubId(attached);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_PERSON_CLUB, pcId, toClubSnapshot(attached)));
        return toResponse(saved);
    }

    public PersonResponse updateCurrentClubMembership(PersonId id, PersonClubRequest req) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        PersonClubResponse beforeSnapshot = toClubSnapshot(aliveMembershipInOrThrow(p, tenant));
        PersonClub pc = p.updateClubMembership(
                tenant,
                req.memberNumber(),
                req.memberStateId(),
                PersonMapper.rolesFrom(req),
                PersonMapper.prefsFrom(req),
                req.isActive());
        Person saved = persistPerson(p);
        UUID pcId = requirePersonClubId(pc);
        PersonResponse after = toResponse(saved);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_PERSON_CLUB, pcId,
                        beforeSnapshot, toClubSnapshot(pc)));
        return after;
    }

    public void leaveCurrentClub(PersonId id, @Nullable UUID userId) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        PersonClub membershipBeforeLeave = aliveMembershipInOrThrow(p, tenant);
        UUID personClubIdCapturedBeforeLeave = requirePersonClubId(membershipBeforeLeave);
        PersonClubResponse beforeSnapshot = toClubSnapshot(membershipBeforeLeave);
        p.leaveClub(tenant, userId, clock);
        persistPerson(p);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_PERSON_CLUB, personClubIdCapturedBeforeLeave,
                        beforeSnapshot));
    }

    @Transactional(readOnly = true)
    public PersonLookupResult lookup(PersonLookupRequest req) {
        UUID tenant = currentTenantOrThrow();
        List<Person> matches;
        if (req.email() != null && !req.email().isBlank()) {
            matches = persons.findActiveByEmailInSameDeploymentAs(
                    req.email().trim().toLowerCase(Locale.ROOT), tenant);
        } else if (req.firstname() != null && req.lastname() != null && req.birthday() != null) {
            matches = persons.findActiveByIdentityTripleInSameDeploymentAs(
                    req.firstname().strip(),
                    req.lastname().strip(),
                    req.birthday(),
                    tenant);
        } else {
            throw new IllegalArgumentException(
                    "Lookup requires email OR full identity triple (firstname + lastname + birthday)");
        }

        List<PersonLookupMatch> capped = matches.stream()
                .limit(LOOKUP_RESULT_CAP)
                .map(p -> PersonMapper.toLookupMatch(
                        p, persons.hasActiveMembershipInCurrentTenant(idValueOrThrow(p))))
                .toList();

        AuditAction action = matches.isEmpty() ? AuditAction.LOOKUP_MISS : AuditAction.LOOKUP_HIT;
        UUID targetId = matches.isEmpty()
                ? correlatableMissTargetIdFromHashedLookupKey(req)
                : idValueOrThrow(matches.get(0));
        auditTrail.record(action,
                AuditedTarget.created("PersonLookup", targetId,
                        new LookupAuditPayload(tenant, req.email() != null, matches.size())));
        return new PersonLookupResult(capped);
    }

    record LookupAuditPayload(UUID tenant, boolean byEmail, int matchCount) {}

    private static UUID correlatableMissTargetIdFromHashedLookupKey(PersonLookupRequest req) {
        String canonical;
        if (req.email() != null && !req.email().isBlank()) {
            canonical = "e|" + req.email().trim().toLowerCase(Locale.ROOT);
        } else {
            canonical = "t|"
                    + (req.firstname() == null ? "" : req.firstname().strip().toLowerCase(Locale.ROOT))
                    + "|"
                    + (req.lastname() == null ? "" : req.lastname().strip().toLowerCase(Locale.ROOT))
                    + "|"
                    + (req.birthday() == null ? "" : req.birthday().toString());
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (digest[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (digest[i] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static UUID requirePersonClubId(PersonClub pc) {
        UUID pcId = pc.getId();
        if (pcId == null) {
            throw new IllegalStateException("PersonClub id is null after persist");
        }
        return pcId;
    }

    private Person loadInCurrentTenantOrThrow(PersonId id) {
        Person p = persons.findActiveById(id.value())
                .orElseThrow(() -> new PersonNotFoundException(id));
        if (!persons.hasActiveMembershipInCurrentTenant(id.value())) {
            throw new PersonNotFoundException(id);
        }
        return p;
    }

    private PersonResponse toResponse(Person p) {
        UUID personId = idValueOrThrow(p);
        int visible = p.getActivePersonClubs().size();
        long all = persons.countActiveMembershipsAcrossTenants(personId);
        int inOther = Math.max(0, (int) (all - visible));
        return PersonMapper.toResponse(p,
                PersonMapper.MemberStateNameLookup.fromList(memberStates.nameRowsInCurrentTenant()),
                inOther);
    }

    private PersonClubResponse toClubSnapshot(PersonClub pc) {
        return PersonMapper.toClubResponse(pc,
                PersonMapper.MemberStateNameLookup.fromList(memberStates.nameRowsInCurrentTenant()));
    }

    private PersonClub applyJoin(Person p, UUID tenant, PersonClubRequest req) {
        return p.joinClub(tenant,
                req.memberNumber(),
                req.memberStateId(),
                PersonMapper.rolesFrom(req),
                PersonMapper.prefsFrom(req),
                req.isActive());
    }

    private Person persistPerson(Person p) {
        Person saved = persons.save(p);
        persons.flush();
        return saved;
    }

    private UUID currentTenantOrThrow() {
        UUID tenant = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
            throw new PersonNotFoundException("No tenant context available — refusing tenant-scoped operation");
        }
        return tenant;
    }

    private static UUID idValueOrThrow(Person p) {
        PersonId id = p.getId();
        if (id == null) {
            throw new IllegalStateException("Person id is null");
        }
        return id.value();
    }
}
