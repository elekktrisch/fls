package ch.alpenflight.migrations.application;

import ch.alpenflight.platform.security.JitUserAttributeStamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Pre-tenant principal → {@code t_user.id} lookup. The S-052
 * {@code UserPrincipalLookup} requires the JIT path (a {@code clubId}
 * claim on the JWT) so federated baseline tokens fall through to its
 * empty branch; the S-140 handshake fires from a verified-email user
 * whose first action is signup — they may not yet have a {@code clubId}
 * claim (Trial-Deployment provisioning lands the claim later, S-138).
 *
 * <p>Identical SQL to
 * {@code ch.alpenflight.platform.tenancy.UserPrincipalLookup#resolveUserIdFor},
 * minus the request-attribute fast-path (the S-140 endpoints are not
 * gated by {@code JitUserMaterializationFilter}, which skips when
 * {@code clubId} is absent — the stash attribute is therefore unset).
 *
 * <p>Stamps the JIT filter's request attribute on hit via
 * {@link JitUserAttributeStamp} so the cross-cutting audit emitter sees
 * the resolved user id without a second JDBC roundtrip.
 */
@Component
public class PreTenantUserLookup {

    private static final Logger LOG = LoggerFactory.getLogger(PreTenantUserLookup.class);

    private static final String SELECT_USER_ID =
            "SELECT id FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL";

    private final JdbcTemplate jdbc;

    public PreTenantUserLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> resolveUserId(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            // Non-UUID sub (federated IdP shape). Probing signal — log
            // at DEBUG so S-056 can surface repeated misses without
            // bloating the trail for one-off federated logins.
            LOG.debug("PreTenantUserLookup: sub is not a UUID, rejecting; sub={}", sub);
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList(SELECT_USER_ID, UUID.class, parsed.toString());
        if (matches.size() == 1) {
            UUID userId = matches.get(0);
            JitUserAttributeStamp.stampResolvedUserId(userId);
            return Optional.of(userId);
        }
        return Optional.empty();
    }
}
