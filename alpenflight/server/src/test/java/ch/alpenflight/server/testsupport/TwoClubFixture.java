package ch.alpenflight.server.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two-club seeding helper used by the S-024 leakage sweep and the per-aggregate
 * isolation ITs. Pre-creates {@code CLUB_A} and {@code CLUB_B} under stable
 * UUIDs so tests can {@code TenantTestContext.runAs(CLUB_A, ...)} without
 * also having to construct a tenant row.
 *
 * <p>Per ADR 0021 — pre-clean by stable test-name keys at seed time, no
 * {@code @AfterEach}. Per-class instances pick their own UUID pair + prefix
 * so multiple {@code IT}s coexist in the JVM without colliding.
 *
 * <p>{@link #seed()} also clears tenant-scoped child rows under the two
 * clubs via {@link #deleteTenantScopedRows()} — driven by
 * {@link TenantScopedEntityCatalog} so a new {@code @TenantId} entity lands
 * its cleanup automatically. Aggregate-internal child tables that lack a
 * direct {@code club_id} (today: {@code inoutbound_point}) are handled by
 * an explicit reverse-dependency delete.
 */
public final class TwoClubFixture {

    private final JdbcTemplate jdbc;
    private final UUID clubA;
    private final UUID clubB;
    private final String namePrefix;
    private final String keyPrefix;

    public TwoClubFixture(JdbcTemplate jdbc, UUID clubA, UUID clubB,
                          String namePrefix, String keyPrefix) {
        this.jdbc = jdbc;
        this.clubA = clubA;
        this.clubB = clubB;
        this.namePrefix = namePrefix;
        this.keyPrefix = keyPrefix;
    }

    public UUID clubA() { return clubA; }
    public UUID clubB() { return clubB; }

    /** Wipes prior tenant rows + clubs, then inserts the two test clubs. */
    public void seed() {
        deleteTenantScopedRows();
        deleteClubs();
        insertClub(clubA, "alpha");
        insertClub(clubB, "bravo");
    }

    public void deleteClubs() {
        jdbc.update("DELETE FROM t_club WHERE id IN (?::uuid, ?::uuid)",
                clubA.toString(), clubB.toString());
    }

    /** Deletes every tenant-scoped row under the two seed clubs. */
    public void deleteTenantScopedRows() {
        // Aggregate-internal child via parent FK chain — handled before
        // location's own delete. Today's only such case; future stories add
        // siblings here as they land.
        jdbc.update("DELETE FROM t_inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM t_location WHERE club_id IN (?::uuid, ?::uuid))",
                clubA.toString(), clubB.toString());
        // Flight rows hold a FK to Aircraft (ON DELETE RESTRICT). Delete flights
        // up-front so the aircraft cleanup below isn't blocked. CASCADE handles
        // flight_crew via the schema-level FK.
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id IN (?::uuid, ?::uuid)",
                clubA.toString(), clubB.toString());
        // Aircraft is cross-tenant since S-058 (reverts S-159), so it's no longer
        // in the catalog loop below. But managing_club_id → club is ON DELETE
        // RESTRICT, so aircraft rows under the seed clubs would block
        // deleteClubs(). Wipe explicitly; aircraft_aircraft_state and
        // aircraft_operating_counter cascade via their FKs.
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id IN (?::uuid, ?::uuid)",
                clubA.toString(), clubB.toString());
        for (Class<?> entityClass : TenantScopedEntityCatalog.discoverTenantScopedEntities()) {
            String table = TenantScopedEntityCatalog.resolveTableName(entityClass);
            String tenantCol = TenantScopedEntityCatalog.resolveTenantColumnName(entityClass);
            jdbc.update("DELETE FROM " + table + " WHERE " + tenantCol + " IN (?::uuid, ?::uuid)",
                    clubA.toString(), clubB.toString());
        }
    }

    private void insertClub(UUID id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        // deployment_id defaults to the operator Deployment via the V14
        // column DEFAULT — IT fixtures don't need to surface it.
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                                  slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                """,
                id.toString(),
                namePrefix + slug,
                keyPrefix + slug.charAt(0),
                countryId.toString(),
                clubStateId.toString(),
                namePrefix + slug);
    }
}
