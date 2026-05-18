package ch.alpenflight.platform.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves a tenant by looking the authenticated user up in the {@code user}
 * table. Used for two paths in the resolver chain: federated-issuer DB-verify
 * (claim present but issuer not on the trusted allowlist) and claim-absent
 * fallback (Google / Auth0 baseline — no {@code clubId} claim at all).
 *
 * <p>{@link JdbcTemplate}, not JPA — the calling resolver runs inside
 * Hibernate's session-open path, so opening another JPA session would
 * recurse. {@code user} carries no {@code @TenantId} (per V2 it's a
 * cross-tenant identity row), so the raw JDBC path doesn't bypass any
 * filter it should have honored.
 *
 * <p>Lookup order: {@code keycloak_sub} first (UNIQUE per S-012); then
 * {@code lower(notification_email)} but only when the JWT claims
 * {@code email_verified=true} AND exactly one non-deleted {@code user} row
 * matches. Multiple matches or unverified email → empty (resolver returns
 * sentinel). Email is never logged.
 *
 * <p>Disabled under {@code mock-auth} — the hardcoded mock principal carries
 * a non-null {@code clubId} claim, the resolver consumes it directly, and
 * no DB users are seeded.
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
        Optional<UUID> bySub = bySubject(jwt);
        if (bySub.isPresent()) {
            LOG.debug("tenant-lookup sub-hit sub={}", jwt.getSubject());
            return bySub;
        }
        Optional<UUID> byEmail = byVerifiedEmail(jwt);
        if (byEmail.isPresent()) {
            LOG.debug("tenant-lookup email-hit sub={}", jwt.getSubject());
            return byEmail;
        }
        LOG.debug("tenant-lookup miss sub={}", jwt.getSubject());
        return Optional.empty();
    }

    private Optional<UUID> bySubject(Jwt jwt) {
        UUID sub = parseUuid(jwt.getSubject());
        if (sub == null) {
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList(
                "SELECT club_id FROM \"user\" WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                UUID.class, sub.toString());
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private Optional<UUID> byVerifiedEmail(Jwt jwt) {
        Boolean verified = jwt.getClaimAsBoolean("email_verified");
        if (!Boolean.TRUE.equals(verified)) {
            return Optional.empty();
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList(
                """
                SELECT club_id FROM "user"
                WHERE lower(notification_email) = lower(?)
                  AND email_confirmed = true
                  AND deleted_on IS NULL
                """,
                UUID.class, email);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static @Nullable UUID parseUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
