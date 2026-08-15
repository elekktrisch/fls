package ch.alpenflight.joinrequests.infra;

import ch.alpenflight.joinrequests.domain.JoinRequestTenantLookup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcJoinRequestTenantLookup implements JoinRequestTenantLookup {

    private static final String LATEST_CLUB_BY_SUB =
            "SELECT club_id FROM t_join_request WHERE keycloak_sub = ?::uuid "
                    + "ORDER BY created_on DESC LIMIT 1";

    private static final String CLUB_BY_ID =
            "SELECT club_id FROM t_join_request WHERE id = ?::uuid";

    private final JdbcTemplate jdbc;

    JdbcJoinRequestTenantLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findLatestClubIdBySub(UUID keycloakSub) {
        return single(LATEST_CLUB_BY_SUB, keycloakSub);
    }

    @Override
    public Optional<UUID> findClubIdById(UUID id) {
        return single(CLUB_BY_ID, id);
    }

    private Optional<UUID> single(String sql, UUID key) {
        List<UUID> rows = jdbc.queryForList(sql, UUID.class, key.toString());
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
    }
}
