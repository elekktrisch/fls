package ch.alpenflight.migrations.application;

import ch.alpenflight.platform.security.JitUserMaterializationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
 */
@Component
public class PreTenantUserLookup {

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
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList(SELECT_USER_ID, UUID.class, parsed.toString());
        if (matches.size() == 1) {
            UUID userId = matches.get(0);
            // Stamp the request attribute so the cross-cutting ActorResolver
            // (via UserPrincipalLookup) sees the resolved userId without a
            // second JDBC roundtrip. The JIT filter skipped this principal
            // (no clubId claim) and stashed ABSENT, so without this overwrite
            // the audit row would land with actor_user_id=null.
            stampRequestAttribute(userId);
            return Optional.of(userId);
        }
        return Optional.empty();
    }

    private static void stampRequestAttribute(UUID userId) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(JitUserMaterializationFilter.USER_ID_ATTRIBUTE, userId);
        }
    }

    private static @Nullable HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
