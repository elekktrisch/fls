package ch.alpenflight.users.application;

import ch.alpenflight.platform.security.JitUserMaterializer;
import ch.alpenflight.platform.security.UserDeactivatedException;
import ch.alpenflight.platform.tenancy.LanguageCodeLookup;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Wires the cross-cutting {@link JitUserMaterializer} port to the Users
 * aggregate. The materializer itself is non-transactional; each call into
 * {@link UsersService#materializeFromJwt} flows through the {@code UsersService}
 * proxy and opens its own {@code @Transactional} boundary. That tx is
 * independent of the inbound request's transaction, so a materialise
 * failure rolls back without poisoning the controller's tx — and the
 * race-loser catch can re-read in a fresh tx after the winner's commit.
 *
 * <p>Soft-delete gate: any matching row with {@code deleted_on IS NOT
 * NULL} surfaces as {@link UserDeactivatedException}. The tombstone's
 * {@code keycloak_sub} stays set after soft-delete so the gate fires for
 * residual-JWT requests; the re-invite flow clears the tombstone's sub
 * before inserting a new row.
 *
 * <p>Race-loser: the DB partial UNIQUE on {@code keycloak_sub} is the
 * structural net for two-concurrent-first-login. The loser catches
 * {@link DataIntegrityViolationException} from {@code flush()}, re-reads,
 * returns the winner's id. No retry loop.
 */
@Component
class JitUserMaterializerImpl implements JitUserMaterializer {

    private static final Logger LOG = LoggerFactory.getLogger(JitUserMaterializerImpl.class);

    private final UsersService usersService;
    private final UserRepository users;
    private final LanguageCodeLookup languageCodeLookup;
    private final Counter outcomeCreated;
    private final Counter outcomeAlreadyPresent;
    private final Counter outcomeSkippedNoClubId;
    private final Counter outcomeSkippedMalformed;
    private final Counter outcomeSkippedDeactivated;

    JitUserMaterializerImpl(UsersService usersService,
                            UserRepository users,
                            LanguageCodeLookup languageCodeLookup,
                            MeterRegistry meters) {
        this.usersService = usersService;
        this.users = users;
        this.languageCodeLookup = languageCodeLookup;
        this.outcomeCreated = outcome(meters, "created");
        this.outcomeAlreadyPresent = outcome(meters, "already-present");
        this.outcomeSkippedNoClubId = outcome(meters, "skipped-no-clubid");
        this.outcomeSkippedMalformed = outcome(meters, "skipped-malformed");
        this.outcomeSkippedDeactivated = outcome(meters, "skipped-deactivated");
    }

    @Override
    public Optional<UUID> materialize(Jwt jwt) {
        String rawSub = jwt.getSubject();
        if (rawSub == null || rawSub.isBlank()) {
            outcomeSkippedMalformed.increment();
            return Optional.empty();
        }
        UUID sub;
        try {
            sub = UUID.fromString(rawSub);
        } catch (IllegalArgumentException e) {
            outcomeSkippedMalformed.increment();
            return Optional.empty();
        }
        String rawClubId = jwt.getClaimAsString("clubId");
        if (rawClubId == null || rawClubId.isBlank()) {
            outcomeSkippedNoClubId.increment();
            return Optional.empty();
        }
        try {
            UUID.fromString(rawClubId);
        } catch (IllegalArgumentException e) {
            outcomeSkippedNoClubId.increment();
            return Optional.empty();
        }

        Optional<User> existing = users.findAnyByKeycloakSub(sub);
        if (existing.isPresent()) {
            User row = existing.get();
            if (!row.isActive()) {
                outcomeSkippedDeactivated.increment();
                LOG.warn("JIT soft-delete gate fired sub={}", sub);
                throw new UserDeactivatedException("User account is deactivated");
            }
            outcomeAlreadyPresent.increment();
            UUID rowId = idOf(row);
            return Optional.of(rowId);
        }

        // Materialise inputs — username / friendlyName / email — must be
        // present, else there's no buildable User aggregate.
        String username = jwt.getClaimAsString("preferred_username");
        String friendlyName = jwt.getClaimAsString("given_name");
        String email = jwt.getClaimAsString("email");
        if (username == null || friendlyName == null || email == null) {
            outcomeSkippedMalformed.increment();
            LOG.warn("JIT skipping sub={} — missing one of preferred_username / given_name / email",
                    sub);
            return Optional.empty();
        }

        UUID languageId = languageCodeLookup.resolve(jwt.getClaimAsString("locale"));
        try {
            UUID rowId = usersService.materializeFromJwt(jwt, languageId);
            outcomeCreated.increment();
            LOG.info("JIT materialised user row sub={} clubId={} languageId={} userId={}",
                    sub, rawClubId, languageId, rowId);
            return Optional.of(rowId);
        } catch (DataIntegrityViolationException race) {
            // Race-loser: the winning thread created the row inside its
            // own tx; re-read and return their id. Counts as
            // already-present so DoS alerting on `created` rate isn't
            // inflated by the race-loser cohort.
            outcomeAlreadyPresent.increment();
            Optional<UUID> bySub = users.findActiveByKeycloakSub(sub)
                    .map(JitUserMaterializerImpl::idOf);
            if (bySub.isPresent()) {
                return bySub;
            }
            // By-sub re-read missed even though the username insert collided:
            // the active row holding this `preferred_username` carries a
            // DIFFERENT sub (the same person re-appearing under a fresh KC
            // sub — admin recreate, realm re-import, or a concurrent
            // first-login whose username-winning row was a sibling
            // principal's). Reconcile that row's sub to this JWT's sub
            // (tenant-guarded in the service) so JIT is idempotent on
            // username — without it the principal is left permanently
            // tenant-less and every @TenantId read misses its club_id
            // (J-2 T-22 / T-23 silent-tenant-less gap).
            UUID clubId = UUID.fromString(rawClubId);
            return usersService.reconcileSubByUsername(username, sub, clubId);
        }
    }

    private static UUID idOf(User u) {
        var id = u.getId();
        if (id == null) {
            throw new IllegalStateException("User row missing id post-load");
        }
        return id.value();
    }

    private static Counter outcome(MeterRegistry meters, String outcome) {
        return Counter.builder("users.jit.outcome")
                .description("First-login JIT materialise outcome counter")
                .tag("outcome", outcome)
                .register(meters);
    }
}
