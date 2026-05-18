package ch.alpenflight.clubs;

import ch.alpenflight.clubs.ClubDtos.ClubCreateRequest;
import ch.alpenflight.clubs.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.ClubDtos.ClubUpdateRequest;
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
 * <p>The walking-skeleton create path uses the canonical seed UUIDs for
 * {@code country_id} (Switzerland) and {@code club_state_id} (ACTIVE).
 * S-047 will introduce real pickers; until then, all new clubs land under
 * those defaults.
 */
@Service
@Transactional
public class ClubsService {

    // Canonical seed UUIDs — see V2 reference seeds + V5 walking-skeleton row.
    // Hard-coded for the walking skeleton; S-047 replaces with FK pickers.
    private static final UUID DEFAULT_COUNTRY_ID =
            UUID.fromString("019e2e15-2c00-74be-8000-0000000004be"); // CH
    private static final UUID DEFAULT_CLUB_STATE_ID =
            UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8"); // ACTIVE

    private final ClubsRepository clubs;

    public ClubsService(ClubsRepository clubs) {
        this.clubs = clubs;
    }

    @Transactional(readOnly = true)
    public List<ClubResponse> listClubs() {
        return clubs.findAllActive().stream().map(ClubMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClubResponse getClub(UUID id) {
        return clubs.findActiveById(id)
                .map(ClubMapper::toResponse)
                .orElseThrow(() -> new ClubNotFoundException(id));
    }

    public ClubResponse createClub(ClubCreateRequest req) {
        if (clubs.existsBySlug(req.slug())) {
            throw new SlugAlreadyExistsException(req.slug());
        }
        Club club = Club.create(
                req.name(),
                req.slug(),
                req.clubKey(),
                req.publicRegistrationEnabled(),
                DEFAULT_COUNTRY_ID,
                DEFAULT_CLUB_STATE_ID);
        try {
            return ClubMapper.toResponse(clubs.save(club));
        } catch (DataIntegrityViolationException e) {
            // Race with a concurrent create — partial UNIQUE is the gate.
            throw new SlugAlreadyExistsException(req.slug());
        }
    }

    public ClubResponse updateClub(UUID id, ClubUpdateRequest req) {
        Club club = clubs.findActiveById(id)
                .orElseThrow(() -> new ClubNotFoundException(id));
        if (clubs.existsBySlugExcluding(req.slug(), id)) {
            throw new SlugAlreadyExistsException(req.slug());
        }
        club.rename(req.name());
        club.rebrand(req.slug());
        if (req.publicRegistrationEnabled()) {
            club.enablePublicRegistration();
        } else {
            club.disablePublicRegistration();
        }
        try {
            return ClubMapper.toResponse(clubs.save(club));
        } catch (DataIntegrityViolationException e) {
            throw new SlugAlreadyExistsException(req.slug());
        }
    }

    public void deleteClub(UUID id) {
        Club club = clubs.findActiveById(id)
                .orElseThrow(() -> new ClubNotFoundException(id));
        clubs.delete(club);
    }
}
