package ch.alpenflight.joinrequests.infra;

import ch.alpenflight.joinrequests.domain.JoinRequestTenantLookup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC implementation of {@link JoinRequestTenantLookup} (S-178). Registered
 * in {@code native-sql-register.md} as {@code join-request-pre-tenant-club-lookup}:
 * {@code t_join_request} is {@code @TenantId}-scoped, so a JPA finder under the
 * pilot's {@code NO_TENANT} context returns zero rows. These two reads
 * deliberately run unfiltered — they exist to LEARN the tenant the pilot's own
 * request belongs to, before the service re-enters JPA under
 * {@code Tenants.runAs(clubId)}.
 *
 * <p>Both queries are keyed on caller-bound parameters — the JWT subject (for
 * the me-read) or the path-variable request id (for withdraw) — never string
 * interpolation. The me-read additionally checks {@code keycloak_sub}, so a
 * caller can only ever resolve the tenant of a request they own; the withdraw
 * resolution is re-gated by a {@code keycloak_sub == jwt.sub} ownership check
 * in the service after the aggregate loads.
 */
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
