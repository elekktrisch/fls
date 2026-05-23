package ch.alpenflight.persons.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.persons.application.PersonDtos.PersonClubRequest;
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
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@link Person} aggregate.
 *
 * <p><strong>Tenant discipline</strong>: Person is cross-tenant; PersonClub
 * is tenant-scoped via Hibernate {@code @TenantId}. The service NEVER calls
 * {@code repository.findAll()} — the only legal multi-row read is
 * {@link PersonRepository#findActiveListRowsInCurrentTenant()} which JOINs
 * through {@code person_club} so the tenant predicate fires automatically.
 * Single-Person reads use PK-load + a caller-tenant existence check
 * ({@link PersonRepository#hasActiveMembershipInCurrentTenant}); a Person
 * whose only PersonClub is in another tenant surfaces as 404, never 403
 * (the IDOR contract).
 *
 * <p>Soft-delete is structurally safe: {@link Person#softDelete} consults
 * {@link PersonRepository#hasActiveMembershipInOtherTenant} (native SQL —
 * deliberate {@code @TenantId} escape) and refuses if another tenant still
 * references the row.
 *
 * <p>Per S-027, mutating methods emit {@link AuditAction#CREATE} /
 * {@link AuditAction#UPDATE} / {@link AuditAction#DELETE} via
 * {@link AuditTrail}. Person mutations land with caller-tenant scope on
 * {@code tenant_club_id}; the {@code mutation_audit_event} payload columns
 * carry {@code [redacted]} for Person because {@code Person} is in the
 * {@code audit.redaction.deny-all} policy (S-027) — admins get the "what"
 * (action + actor + target id), not the "what changed."
 */
@Service
@Transactional
public class PersonsService {

    private static final String AUDIT_PERSON = "Person";
    private static final String AUDIT_PERSON_CLUB = "PersonClub";
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
        // before/after are the Person aggregates themselves; the deny-all
        // policy on Person serialises both as [redacted]. We still pass them
        // so S-056 sees the action + actor + target id with consistent shape.
        Person beforeSnapshot = p;
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

    public void softDeletePerson(PersonId id, @Nullable UUID userId) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        boolean inOtherTenant = persons.hasActiveMembershipInOtherTenant(id.value(), tenant);
        p.softDelete(userId, clock, inOtherTenant);
        persistPerson(p);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_PERSON, id.value(), p));
    }

    public PersonResponse attachExistingPerson(PersonId id, PersonClubRequest req) {
        Person p = persons.findActiveById(id.value())
                .orElseThrow(() -> new PersonNotFoundException(id));
        UUID tenant = currentTenantOrThrow();
        applyJoin(p, tenant, req);
        Person saved = persistPerson(p);
        PersonResponse after = toResponse(saved);
        // Audit the join: target is the (just-attached) PersonClub.
        UUID pcId = saved.getActivePersonClubs().stream()
                .filter(pc -> tenant.equals(pc.getClubId()))
                .findFirst()
                .map(pc -> {
                    UUID childId = pc.getId();
                    if (childId == null) {
                        throw new IllegalStateException("PersonClub id missing after persist");
                    }
                    return childId;
                })
                .orElseThrow(() -> new IllegalStateException("attached PersonClub missing"));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_PERSON_CLUB, pcId, saved));
        return after;
    }

    public PersonResponse updateCurrentClubMembership(PersonId id, PersonClubRequest req) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        PersonClub pc = p.updateClubMembership(
                tenant,
                req.memberNumber(),
                req.memberStateId(),
                PersonMapper.rolesFrom(req),
                PersonMapper.prefsFrom(req),
                req.isActive());
        Person saved = persistPerson(p);
        UUID pcId = pc.getId();
        if (pcId == null) {
            throw new IllegalStateException("PersonClub id is null after update");
        }
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_PERSON_CLUB, pcId, saved, saved));
        return toResponse(saved);
    }

    public void leaveCurrentClub(PersonId id, @Nullable UUID userId) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        p.leaveClub(tenant, userId, clock);
        persistPerson(p);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_PERSON_CLUB, id.value(), p));
    }

    @Transactional(readOnly = true)
    public PersonLookupResult lookup(PersonLookupRequest req) {
        UUID tenant = currentTenantOrThrow();
        List<Person> matches;
        if (req.email() != null && !req.email().isBlank()) {
            matches = persons.findActiveByEmail(req.email().toLowerCase(Locale.ROOT));
        } else if (req.firstname() != null && req.lastname() != null && req.birthday() != null) {
            matches = persons.findActiveByIdentityTriple(
                    req.firstname().strip(),
                    req.lastname().strip(),
                    req.birthday());
        } else {
            // The DTO's @AssertTrue should have rejected this at the boundary.
            throw new IllegalArgumentException(
                    "Lookup requires email OR full identity triple (firstname + lastname + birthday)");
        }

        List<PersonLookupMatch> capped = matches.stream()
                .limit(LOOKUP_RESULT_CAP)
                .map(p -> PersonMapper.toLookupMatch(
                        p, persons.hasActiveMembershipInCurrentTenant(idValueOrThrow(p))))
                .toList();

        // Audit both hit + miss — the negative response is information disclosure.
        // (entityType, entityId) disambiguates: `PersonLookup` + first-hit-id
        // or nil-UUID on miss. A dedicated PERSON_LOOKUP_HIT/MISS AuditAction
        // is a hardening follow-up; today we use UPDATE as the neutral marker.
        UUID firstHitId = matches.isEmpty() ? new UUID(0L, 0L) : idValueOrThrow(matches.get(0));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.created("PersonLookup", firstHitId,
                        new LookupAuditPayload(tenant, req.email() != null, matches.size())));
        return new PersonLookupResult(capped);
    }

    /** Minimal payload that survives default-deny redaction (only primitives). */
    record LookupAuditPayload(UUID tenant, boolean byEmail, int matchCount) {}

    /** Internal helper: load by PK + assert the caller's tenant sees an alive PersonClub. */
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

    private void applyJoin(Person p, UUID tenant, PersonClubRequest req) {
        p.joinClub(tenant,
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
