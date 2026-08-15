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

@Component
public class PreTenantUserLookup {

    private static final Logger LOG = LoggerFactory.getLogger(PreTenantUserLookup.class);

    private static final String SELECT_USER_ID =
            "SELECT id FROM t_user WHERE keycloak_sub = ? AND deleted_on IS NULL";

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
            LOG.debug("PreTenantUserLookup: sub is not a UUID, rejecting; sub={}", sub);
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList(SELECT_USER_ID, UUID.class, parsed);
        if (matches.size() == 1) {
            UUID userId = matches.get(0);
            JitUserAttributeStamp.stampResolvedUserId(userId);
            return Optional.of(userId);
        }
        return Optional.empty();
    }
}
