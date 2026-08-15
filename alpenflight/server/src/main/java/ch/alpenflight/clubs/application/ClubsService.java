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
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ClubsService {

    private static final String AUDIT_ENTITY_TYPE = "Club";

    private final ClubRepository clubs;
    private final CountryRepository countries;
    private final ClubStateRepository clubStates;
    private final ClubLocationLookup clubLocations;
    private final Clock clock;
    private final AuditTrail auditTrail;
    private final JoinCodeGenerator joinCodes;

    public ClubsService(ClubRepository clubs,
                        CountryRepository countries,
                        ClubStateRepository clubStates,
                        ClubLocationLookup clubLocations,
                        Clock clock,
                        AuditTrail auditTrail,
                        JoinCodeGenerator joinCodes) {
        this.clubs = clubs;
        this.countries = countries;
        this.clubStates = clubStates;
        this.clubLocations = clubLocations;
        this.clock = clock;
        this.auditTrail = auditTrail;
        this.joinCodes = joinCodes;
    }

    @Transactional(readOnly = true)
    public List<ClubResponse> listClubs() {
        return clubs.findAllActive().stream().map(ClubMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long countActiveClubs() {
        return clubs.countActive();
    }

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

    public JoinCodeResponse rotateJoinCode(ClubId id) {
        Club club = clubs.findActiveById(id.value())
                .orElseThrow(() -> new ClubNotFoundException(id));
        String newCode = club.rotateJoinCode(joinCodes, candidate -> !clubs.existsByJoinCodeIncludingDeleted(candidate));
        clubs.save(club);
        auditTrail.record(AuditAction.CLUB_JOIN_CODE_ROTATED,
                new AuditedTarget(AUDIT_ENTITY_TYPE, id.value(), null, null));
        return new JoinCodeResponse(newCode);
    }

    public ClubResponse createClub(ClubCreateRequest req) {
        if (clubs.existsBySlug(req.slug())) {
            throw new SlugAlreadyExistsException(req.slug());
        }
        validateReferences(req.countryId(), req.clubStateId());
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
        club.setRegistrationOperatorEmails(
                req.discoveryFlightOperatorEmail(), req.scenicFlightOperatorEmail());
        club.setDiscoveryFlightType(
                req.discoveryFlightTypeId() == null ? null : req.discoveryFlightTypeId().value());
        club.relocateHomebase(
                req.homebaseId() == null ? null : req.homebaseId().value(),
                locationId -> ownsActiveLocation(id.value(), locationId));
        ClubResponse after = ClubMapper.toResponse(persist(club, req.slug()));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    private Club persist(Club club, String slug) {
        try {
            return clubs.save(club);
        } catch (DataIntegrityViolationException e) {
            throw discriminateByViolatedConstraint(e, club.getClubKey(), slug);
        }
    }

    private static RuntimeException discriminateByViolatedConstraint(
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

    private boolean ownsActiveLocation(UUID clubId, UUID locationId) {
        return Tenants.runAs(clubId,
                () -> clubLocations.isActiveLocationOfCurrentTenant(locationId));
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
