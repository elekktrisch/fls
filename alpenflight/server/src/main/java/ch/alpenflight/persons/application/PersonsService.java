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
        // Snapshot the read DTO BEFORE mutating the aggregate. The audit
        // listener serialises whatever object reference it receives; if we
        // pass the in-place-mutated `p`, before/after are the same Java
        // object and the diff is empty even before redaction runs.
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

    public void softDeletePerson(PersonId id, @Nullable UUID userId) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        boolean inOtherTenant = persons.hasActiveMembershipInOtherTenant(id.value(), tenant);
        // Snapshot the response DTO before softDelete mutates deletedOn.
        PersonResponse beforeSnapshot = toResponse(p);
        p.softDelete(userId, clock, inOtherTenant);
        persistPerson(p);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_PERSON, id.value(), beforeSnapshot));
    }

    public PersonResponse attachExistingPerson(PersonId id, PersonClubRequest req) {
        Person p = persons.findActiveById(id.value())
                .orElseThrow(() -> new PersonNotFoundException(id));
        UUID tenant = currentTenantOrThrow();
        // joinClub returns the attached PersonClub; persistPerson flushes so
        // the generated UUID lands on the in-memory reference.
        PersonClub attached = applyJoin(p, tenant, req);
        Person saved = persistPerson(p);
        UUID pcId = requirePersonClubId(attached);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_PERSON_CLUB, pcId, saved));
        return toResponse(saved);
    }

    public PersonResponse updateCurrentClubMembership(PersonId id, PersonClubRequest req) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        // Snapshot the membership-bearing response DTO before mutating.
        PersonResponse beforeSnapshot = toResponse(p);
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
                AuditedTarget.updated(AUDIT_PERSON_CLUB, pcId, beforeSnapshot, after));
        return after;
    }

    public void leaveCurrentClub(PersonId id, @Nullable UUID userId) {
        Person p = loadInCurrentTenantOrThrow(id);
        UUID tenant = currentTenantOrThrow();
        // Capture the soft-deleted PersonClub id BEFORE the aggregate marks
        // it deleted — `leaveClub` flips `deleted_on` in place, after which
        // `getActivePersonClubs()` no longer sees the row.
        UUID pcId = p.getActivePersonClubs().stream()
                .filter(pc -> tenant.equals(pc.getClubId()))
                .findFirst()
                .map(PersonsService::requirePersonClubId)
                .orElseThrow(() -> new PersonNotFoundException(
                        "Person has no alive PersonClub in club " + tenant));
        p.leaveClub(tenant, userId, clock);
        persistPerson(p);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_PERSON_CLUB, pcId, p));
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

        // Audit both hit and miss — the negative response is itself a
        // disclosure on cross-tenant directory probes. The miss-target is a
        // SHA-256-derived UUID over the canonical lookup key so repeated
        // misses for the same probe correlate (and don't collide with real
        // Person ids the way nil-UUID would).
        AuditAction action = matches.isEmpty() ? AuditAction.LOOKUP_MISS : AuditAction.LOOKUP_HIT;
        UUID targetId = matches.isEmpty()
                ? hashLookupKey(req)
                : idValueOrThrow(matches.get(0));
        auditTrail.record(action,
                AuditedTarget.created("PersonLookup", targetId,
                        new LookupAuditPayload(tenant, req.email() != null, matches.size())));
        return new PersonLookupResult(capped);
    }

    /** Minimal payload that survives default-deny redaction (only primitives). */
    record LookupAuditPayload(UUID tenant, boolean byEmail, int matchCount) {}

    /**
     * Canonicalise the lookup key + SHA-256 it, then fold the first 16 bytes
     * into a UUID. Lets two lookup-miss audit rows for the same probe key
     * correlate without leaking the queried value into the audit table.
     */
    private static UUID hashLookupKey(PersonLookupRequest req) {
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
