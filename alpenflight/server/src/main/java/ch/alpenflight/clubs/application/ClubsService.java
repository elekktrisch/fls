package ch.alpenflight.clubs.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.application.ClubDtos.ClubCreateRequest;
import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.application.ClubDtos.ClubUpdateRequest;
import ch.alpenflight.clubs.application.ClubDtos.JoinCodeResponse;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubKeyAlreadyExistsException;
import ch.alpenflight.clubs.domain.ClubNotFoundException;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.InvalidClubReferenceException;
import ch.alpenflight.clubs.domain.JoinCodeGenerator;
import ch.alpenflight.clubs.domain.SlugAlreadyExistsException;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for {@link Club}. Slug uniqueness is enforced by:
 *
 * <ol>
 *   <li>service-layer pre-check (UX optimization — cleaner 409 mapping for
 *       the non-race case);
 *   <li>partial UNIQUE index {@code ux_club_slug} on {@code club(slug) WHERE
 *       slug IS NOT NULL} (source of truth — wins races).
 * </ol>
 *
 * <p>FK references to {@code t_country} / {@code t_club_state} are pre-checked
 * against the {@link CountryRepository} / {@link ClubStateRepository} domain
 * ports so a bad id surfaces as {@link InvalidClubReferenceException} (HTTP
 * 400) instead of leaking the Postgres FK-violation message; the FK
 * constraint itself stays the source of truth at commit time.
 *
 * <p>External signatures speak {@link ClubId} so service / controller
 * parameter lists can't accidentally swap a {@code Club} id for a
 * {@code Person} / {@code User} id. The repository port still keys on raw
 * {@link UUID} (Spring Data + Hibernate prefer it that way); the service is
 * the seam where the type narrows.
 *
 * <p>Depends on {@link ClubRepository} (domain port) per ADR 0023 — the
 * concrete Spring Data implementation lives in {@code clubs.infra}. The
 * cross-module imports of {@link CountryRepository} / {@link ClubStateRepository}
 * are sanctioned by the {@code referencedata} module's OPEN type per
 * its package-info.
 */
@Service
@Transactional
public class ClubsService {

    private static final String AUDIT_ENTITY_TYPE = "Club";

    private final ClubRepository clubs;
    private final CountryRepository countries;
    private final ClubStateRepository clubStates;
    private final Clock clock;
    private final AuditTrail auditTrail;
    private final JoinCodeGenerator joinCodes;

    public ClubsService(ClubRepository clubs,
                        CountryRepository countries,
                        ClubStateRepository clubStates,
                        Clock clock,
                        AuditTrail auditTrail,
                        JoinCodeGenerator joinCodes) {
        this.clubs = clubs;
        this.countries = countries;
        this.clubStates = clubStates;
        this.clock = clock;
        this.auditTrail = auditTrail;
        this.joinCodes = joinCodes;
    }

    @Transactional(readOnly = true)
    public List<ClubResponse> listClubs() {
        return clubs.findAllActive().stream().map(ClubMapper::toResponse).toList();
    }

    /**
     * Count of active (non-soft-deleted) clubs across the whole deployment —
     * Clubs are the tenant root, never {@code @TenantId}-scoped, so this is a
     * plain unscoped count. Feeds the sysadmin dashboard's {@code totalClubs}
     * tile (J-3 T-10); the {@code me} module composes it via this published
     * API rather than reaching into {@code clubs} internals.
     */
    @Transactional(readOnly = true)
    public long countActiveClubs() {
        return clubs.countActive();
    }

    /**
     * Ids of every active club across the deployment — used by the sysadmin
     * dashboard to iterate the tenant-scoped {@code totalFlights} count one
     * club at a time (each iteration runs under {@code Tenants.runAs(clubId)},
     * J-3 T-10). Clubs are cross-tenant, so this enumeration is itself
     * unscoped.
     */
    @Transactional(readOnly = true)
    public List<UUID> activeClubIds() {
        return clubs.activeIds();
    }

    @Transactional(readOnly = true)
    public ClubResponse getClub(ClubId id, boolean includeJoinCode) {
        Club club = clubs.findActiveById(id.value())
                .orElseThrow(() -> new ClubNotFoundException(id));
        return includeJoinCode ? ClubMapper.toAdminResponse(club) : ClubMapper.toResponse(club);
    }

    /**
     * Rotates the club's join code to a fresh, globally-unique value and
     * returns it. The new code is admin-only output; the audit event carries
     * neither the old nor the new code (S-177 — codes are quasi-secrets).
     */
    public JoinCodeResponse rotateJoinCode(ClubId id) {
        Club club = clubs.findActiveById(id.value())
                .orElseThrow(() -> new ClubNotFoundException(id));
        String newCode = club.rotateJoinCode(joinCodes, candidate -> !clubs.existsByJoinCode(candidate));
        clubs.save(club);
        // Neither snapshot carries the code — codes are quasi-secrets, so an
        // admin reading the audit log must not recover a club's current code.
        // Actor + clubId are stamped on the row by the audit infra regardless.
        auditTrail.record(AuditAction.CLUB_JOIN_CODE_ROTATED,
                new AuditedTarget(AUDIT_ENTITY_TYPE, id.value(), null, null));
        return new JoinCodeResponse(newCode);
    }

    public ClubResponse createClub(ClubCreateRequest req) {
        if (clubs.existsBySlug(req.slug())) {
            throw new SlugAlreadyExistsException(req.slug());
        }
        validateReferences(req.countryId(), req.clubStateId());
        // The admin-managed Clubs surface lives under the operator Deployment
        // (S-137). Self-service ingest (S-138) writes Clubs under the
        // user's TRIAL Deployment via DeploymentProvisioningService — a
        // different code path that constructs Club.create with that
        // Deployment's id directly.
        Club club = Club.create(
                req.name(),
                req.slug(),
                req.clubKey(),
                req.publicRegistrationEnabled(),
                req.countryId().value(),
                req.clubStateId().value(),
                Deployment.OPERATOR_ID);
        ClubResponse created = ClubMapper.toResponse(persist(club, req.slug()));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id().value(), created));
        return created;
    }

    public ClubResponse updateClub(ClubId id, ClubUpdateRequest req) {
        Club club = clubs.findActiveById(id.value())
                .orElseThrow(() -> new ClubNotFoundException(id));
        ClubResponse before = ClubMapper.toResponse(club);
        if (clubs.existsBySlugExcluding(req.slug(), id.value())) {
            throw new SlugAlreadyExistsException(req.slug());
        }
        validateReferences(req.countryId(), req.clubStateId());
        club.rename(req.name());
        club.rebrand(req.slug());
        if (req.publicRegistrationEnabled()) {
            club.enablePublicRegistration();
        } else {
            club.disablePublicRegistration();
        }
        club.relocate(req.countryId().value(), req.clubStateId().value());
        ClubResponse after = ClubMapper.toResponse(persist(club, req.slug()));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    private Club persist(Club club, String slug) {
        try {
            return clubs.save(club);
        } catch (DataIntegrityViolationException e) {
            // Race-loser path: the UNIQUE indexes win regardless of the
            // service-layer pre-check. Discriminate by violated constraint
            // (J-26 T-07) — before this, ANY integrity violation was labeled
            // a slug conflict. FK violations are pre-empted by
            // validateReferences above; an unrecognized constraint propagates
            // (a genuine bug deserves its 500, not a slug mislabel). NOTE:
            // Hibernate defers the INSERT to the transaction flush, so the
            // common path surfaces the DIVE at commit — OUTSIDE this try;
            // ClubsExceptionHandler#handleDataIntegrity applies the same
            // discrimination there (the T-05 pattern).
            throw discriminate(e, club.getClubKey(), slug);
        }
    }

    private static RuntimeException discriminate(
            DataIntegrityViolationException e, String clubKey, String slug) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ux_club_key")) {
            return new ClubKeyAlreadyExistsException(clubKey);
        }
        if (message.contains("ux_club_slug")) {
            return new SlugAlreadyExistsException(slug);
        }
        return e;
    }

    private void validateReferences(CountryId countryId, ClubStateId clubStateId) {
        if (!countries.existsById(countryId.value())) {
            throw new InvalidClubReferenceException("countryId");
        }
        if (!clubStates.existsById(clubStateId.value())) {
            throw new InvalidClubReferenceException("clubStateId");
        }
    }

    public void deleteClub(ClubId id) {
        Club club = clubs.findActiveById(id.value())
                .orElseThrow(() -> new ClubNotFoundException(id));
        ClubResponse before = ClubMapper.toResponse(club);
        club.softDelete(clock);
        clubs.save(club);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id.value(), before));
    }
}
