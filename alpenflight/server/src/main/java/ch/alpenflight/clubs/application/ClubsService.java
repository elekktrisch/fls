package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.application.ClubDtos.ClubCreateRequest;
import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.application.ClubDtos.ClubUpdateRequest;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubNotFoundException;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.InvalidClubReferenceException;
import ch.alpenflight.clubs.domain.SlugAlreadyExistsException;
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
 * <p>FK references to {@code country} / {@code club_state} are pre-checked
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

    private final ClubRepository clubs;
    private final CountryRepository countries;
    private final ClubStateRepository clubStates;
    private final Clock clock;

    public ClubsService(ClubRepository clubs,
                        CountryRepository countries,
                        ClubStateRepository clubStates,
                        Clock clock) {
        this.clubs = clubs;
        this.countries = countries;
        this.clubStates = clubStates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ClubResponse> listClubs() {
        return clubs.findAllActive().stream().map(ClubMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClubResponse getClub(ClubId id) {
        return clubs.findActiveById(id.value())
                .map(ClubMapper::toResponse)
                .orElseThrow(() -> new ClubNotFoundException(id));
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
                req.clubStateId().value());
        return ClubMapper.toResponse(persist(club, req.slug()));
    }

    public ClubResponse updateClub(ClubId id, ClubUpdateRequest req) {
        Club club = clubs.findActiveById(id.value())
                .orElseThrow(() -> new ClubNotFoundException(id));
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
        return ClubMapper.toResponse(persist(club, req.slug()));
    }

    private Club persist(Club club, String slug) {
        try {
            return clubs.save(club);
        } catch (DataIntegrityViolationException e) {
            // Race-loser path: partial UNIQUE on slug wins regardless of the
            // service-layer pre-check. FK violations are pre-empted by
            // validateReferences above; any DIVE here is the slug case.
            throw new SlugAlreadyExistsException(slug);
        }
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
        club.softDelete(clock);
        clubs.save(club);
    }
}
