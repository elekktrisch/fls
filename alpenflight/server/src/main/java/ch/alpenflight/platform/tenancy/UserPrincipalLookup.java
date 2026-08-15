package ch.alpenflight.platform.tenancy;

import ch.alpenflight.platform.security.JitUserMaterializationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class UserPrincipalLookup {

    private static final Logger LOG = LoggerFactory.getLogger(UserPrincipalLookup.class);

    private static final String SELECT_CLUB_ID = "SELECT club_id FROM t_user "
            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL";
    private static final String SELECT_USER_ID = "SELECT id FROM t_user "
            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL";
    private static final String SELECT_PERSON_ID = "SELECT person_id FROM t_user "
            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL";

    private final JdbcTemplate jdbc;

    public UserPrincipalLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> resolveTenantFor(Jwt jwt) {
        return querySingleUuid(jwt, SELECT_CLUB_ID, "club_id");
    }

    public Optional<UUID> resolveUserIdFor(Jwt jwt) {
        Object stashed = stashedUserIdOnRequest();
        if (stashed == JitUserMaterializationFilter.ABSENT) {
            return Optional.empty();
        }
        if (stashed instanceof UUID uuid) {
            return Optional.of(uuid);
        }
        return querySingleUuid(jwt, SELECT_USER_ID, "user_id");
    }

    public Optional<UUID> resolvePersonIdFor(Jwt jwt) {
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
        List<UUID> matches = jdbc.queryForList(SELECT_PERSON_ID, UUID.class, parsed.toString());
        if (matches.size() == 1 && matches.get(0) != null) {
            return Optional.of(matches.get(0));
        }
        return Optional.empty();
    }

    private static @Nullable Object stashedUserIdOnRequest() {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return null;
        }
        return req.getAttribute(JitUserMaterializationFilter.USER_ID_ATTRIBUTE);
    }

    private static @Nullable HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
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
