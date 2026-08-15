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
            outcomeAlreadyPresent.increment();
            Optional<UUID> bySub = users.findActiveByKeycloakSub(sub)
                    .map(JitUserMaterializerImpl::idOf);
            if (bySub.isPresent()) {
                return bySub;
            }
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
