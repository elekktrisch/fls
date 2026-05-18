package ch.alpenflight.platform.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves a tenant by looking the authenticated user up by
 * {@code keycloak_sub}. Used by the resolver when the JWT lacks a
 * {@code clubId} claim — Keycloak realm tokens carry the claim, federated
 * (Google / Auth0) baseline tokens do not.
 *
 * <p>{@link JdbcTemplate}, not JPA — the calling resolver runs inside
 * Hibernate's session-open path, so opening another JPA session would
 * recurse. {@code user} carries no {@code @TenantId} (it's a cross-tenant
 * identity row per V2), so the raw JDBC path doesn't bypass any filter
 * it should have honored.
 *
 * <p>Lookup is keyed on {@code keycloak_sub} (UNIQUE per S-012) and is
 * only meaningful when the JWT subject is a UUID literal — Keycloak's
 * default sub shape. Non-UUID subjects (Google's numeric IDs) currently
 * return empty; the lookup story for those IdPs ships when they onboard.
 *
 * <p>Disabled under {@code @Profile("mock-auth")}: the resolver injects
 * {@code Optional.empty()} and skips DB calls — the mock principal's
 * hardcoded {@code clubId} claim is always present.
 */
@Component
@Profile("!mock-auth")
public class UserTenantLookup {

    private static final Logger LOG = LoggerFactory.getLogger(UserTenantLookup.class);

    private final JdbcTemplate jdbc;

    public UserTenantLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> resolveTenantFor(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList(
                "SELECT club_id FROM \"user\" WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                UUID.class, parsed.toString());
        if (matches.size() == 1) {
            LOG.debug("tenant-lookup sub-hit sub={}", sub);
            return Optional.of(matches.get(0));
        }
        LOG.debug("tenant-lookup miss sub={} matches={}", sub, matches.size());
        return Optional.empty();
    }
}
