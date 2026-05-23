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

    public void seed() {
        deleteClubs();
        insertClub(clubA, "alpha");
        insertClub(clubB, "bravo");
    }

    public void deleteClubs() {
        jdbc.update("DELETE FROM club WHERE id IN (?::uuid, ?::uuid)",
                clubA.toString(), clubB.toString());
    }

    private void insertClub(UUID id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id,
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
