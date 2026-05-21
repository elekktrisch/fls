package ch.alpenflight.platform.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated user's tenant ({@code club_id}) and internal
 * user id ({@code user.id}) by looking them up on {@code keycloak_sub}.
 * Used by:
 *
 * <ul>
 *   <li>{@link ClubTenantIdentifierResolver} — when the JWT lacks a
 *       {@code clubId} claim (Keycloak realm tokens carry the claim,
 *       federated Google / Auth0 baseline tokens do not).</li>
 *   <li>Audit-trail emitters — translating the JWT subject to the internal
 *       {@code user.id} so the soft-delete / mutation trail records the
 *       same identity across realm-token and federated-token paths.</li>
 * </ul>
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
 */
@Component
public class UserTenantLookup {

    private static final Logger LOG = LoggerFactory.getLogger(UserTenantLookup.class);

    private static final String SELECT_CLUB_ID = "SELECT club_id FROM \"user\" "
            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL";
    private static final String SELECT_USER_ID = "SELECT id FROM \"user\" "
            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL";

    private final JdbcTemplate jdbc;

    public UserTenantLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> resolveTenantFor(Jwt jwt) {
        return querySingleUuid(jwt, SELECT_CLUB_ID, "club_id");
    }

    /**
     * Returns the internal {@code user.id} for the JWT subject, or empty if
     * no active {@code user} row matches the sub. Distinct from
     * {@code jwt.getSubject()}: callers wanting "who did this" for audit
     * should prefer this method so federated-IdP paths resolve consistently
     * with realm-token paths.
     */
    public Optional<UUID> resolveUserIdFor(Jwt jwt) {
        return querySingleUuid(jwt, SELECT_USER_ID, "user_id");
    }

    private Optional<UUID> querySingleUuid(Jwt jwt, String sql, String columnLabel) {
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
        List<UUID> matches = jdbc.queryForList(sql, UUID.class, parsed.toString());
        if (matches.size() == 1) {
            LOG.debug("user-lookup hit sub={} column={}", sub, columnLabel);
            return Optional.of(matches.get(0));
        }
        LOG.debug("user-lookup miss sub={} column={} matches={}", sub, columnLabel, matches.size());
        return Optional.empty();
    }
}
