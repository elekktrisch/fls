package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two Delivery / DeliveryItem migration cases a synth bundle can't reach,
 * proven against the REAL bound producer SELECT
 * ({@link MapperLegacyBindings#selectForProducer}) and the REAL Flyway schema —
 * so a regression reds in minutes here, not only at the ~20-min fanout. A synth
 * bundle uses aliased column names and bypasses the producer SELECT entirely, so
 * only a real-producer IT validates the {@code LEFT JOIN Articles} resolution and
 * the schema's absence of a {@code UNIQUE(flight_id)}.
 *
 * <p><strong>Orphan ArticleNumber → keep-null (not 23503).</strong> Legacy
 * {@code DeliveryItems.ArticleNumber} is free text with no FK. The bound
 * {@code DELIVERY_ITEM} producer SELECT {@code LEFT JOIN}s the live Article master
 * keyed by {@code (ClubId, ArticleNumber)} {@code AND a.IsDeleted = 0}. A number
 * matching no live article (free-typed, or only a soft-deleted article) projects a
 * NULL {@code ResolvedArticleId} — the line is kept with a null {@code article_id}
 * (the {@code article_number} snapshot preserved), never a
 * {@code fk_dli_article_id} 23503 that aborts the whole bundle. Regressing the
 * LEFT JOIN to an inner join would drop the orphan line; dropping the
 * {@code IsDeleted = 0} predicate would resolve it onto a soft-deleted article —
 * both fail this test loudly.
 *
 * <p><strong>Multiple deliveries per flight → no 23505.</strong> Legacy permits
 * several {@code Deliveries} on one {@code FlightId} (that is why the write side's
 * delete guard rejects deleting one when {@literal >}1 share the flight). The
 * {@code t_delivery} schema must therefore carry NO {@code UNIQUE(flight_id)}. This
 * inserts two deliveries sharing one flight directly against the migrated schema and
 * asserts both land — a regression adding {@code UNIQUE(flight_id)} would 23505 on
 * the second insert.
 */
@Tag("slow")
class DeliveryCollisionOrphanProducerIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    // seed-club-1 is the canonical dev club — a stable, read-only FK target. The
    // flight the two deliveries share is THIS test's own row (own aircraft + own
    // flight, random ids, seeded per run): the shared dev-seed flight is mutated
    // by sibling write-side ITs in the full suite, so depending on it FK-fails.
    private static final UUID SEED_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID GLIDER_AIRCRAFT_TYPE =
            UUID.fromString("019e2e15-2c00-7af9-8000-000000002af9");
    private static final UUID FLIGHT_PROCESS_STATE_VALID =
            UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");

    private final UUID ownAircraftId = UUID.randomUUID();
    private final UUID ownFlightId = UUID.randomUUID();

    private final UUID clubId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();

    // A line whose ArticleNumber matches a LIVE article — resolves to its id.
    private final UUID liveArticleId = UUID.randomUUID();
    private final UUID resolvableItemId = UUID.randomUUID();
    private final String resolvableNumber = "A-LIVE";
    // A line whose ArticleNumber matches NO live article (only a soft-deleted one
    // of the same number exists) — must resolve to NULL, kept, never 23503.
    private final UUID softDeletedArticleId = UUID.randomUUID();
    private final UUID orphanItemId = UUID.randomUUID();
    private final String orphanNumber = "A-ORPHAN";

    private final UUID dlvA = UUID.randomUUID();
    private final UUID dlvB = UUID.randomUUID();

    @BeforeEach
    void seedLegacyShapedStagingTables() {
        // Legacy-shaped staging tables standing in for the MSSQL tables the
        // DELIVERY_ITEM producer SELECT reads. Unquoted mixed-case identifiers
        // fold to lowercase in Postgres, matching the bound SELECT's unquoted
        // names. BIT IsDeleted is a 0/1 SMALLINT — the SELECT's a.IsDeleted = 0
        // reads it identically on both dialects.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS Deliveries (
                    DeliveryId UUID PRIMARY KEY,
                    ClubId     UUID NOT NULL,
                    FlightId   UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS DeliveryItems (
                    DeliveryItemId        UUID PRIMARY KEY,
                    DeliveryId            UUID NOT NULL,
                    Position              INTEGER NOT NULL,
                    ArticleNumber         TEXT,
                    ItemText              TEXT,
                    AdditionalInformation TEXT,
                    Quantity              DECIMAL(18, 3),
                    DiscountInPercent     INTEGER NOT NULL,
                    UnitType              TEXT,
                    CreatedOn             TIMESTAMP NOT NULL,
                    CreatedByUserId       UUID,
                    ModifiedOn            TIMESTAMP,
                    ModifiedByUserId      UUID,
                    DeletedOn             TIMESTAMP,
                    DeletedByUserId       UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS Articles (
                    ArticleId     UUID PRIMARY KEY,
                    ClubId        UUID NOT NULL,
                    ArticleNumber TEXT NOT NULL,
                    IsDeleted     SMALLINT NOT NULL
                )
                """);
        jdbc.update("DELETE FROM DeliveryItems");
        jdbc.update("DELETE FROM Deliveries");
        jdbc.update("DELETE FROM Articles");

        jdbc.update("INSERT INTO Deliveries (DeliveryId, ClubId, FlightId) VALUES (?, ?, NULL)",
                deliveryId, clubId);

        // One live article the resolvable line keys onto; one soft-deleted article
        // sharing the orphan line's number — the IsDeleted = 0 predicate must skip
        // it so the orphan still resolves to NULL.
        jdbc.update("INSERT INTO Articles (ArticleId, ClubId, ArticleNumber, IsDeleted) "
                + "VALUES (?, ?, ?, 0)", liveArticleId, clubId, resolvableNumber);
        jdbc.update("INSERT INTO Articles (ArticleId, ClubId, ArticleNumber, IsDeleted) "
                + "VALUES (?, ?, ?, 1)", softDeletedArticleId, clubId, orphanNumber);

        insertItem(resolvableItemId, 1, resolvableNumber);
        insertItem(orphanItemId, 2, orphanNumber);

        seedOwnFlight();
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TABLE IF EXISTS DeliveryItems");
        jdbc.execute("DROP TABLE IF EXISTS Deliveries");
        jdbc.execute("DROP TABLE IF EXISTS Articles");
        jdbc.update("DELETE FROM t_delivery WHERE id IN (?::uuid, ?::uuid)",
                dlvA.toString(), dlvB.toString());
        jdbc.update("DELETE FROM t_flight WHERE id = ?::uuid", ownFlightId.toString());
        jdbc.update("DELETE FROM t_aircraft WHERE id = ?::uuid", ownAircraftId.toString());
    }

    // Own aircraft + flight under the stable seed club so the two t_delivery
    // inserts share a flight_id this test fully owns and cleans up — independent
    // of any sibling IT that mutates the shared dev-seed flight.
    private void seedOwnFlight() {
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?,
                        false, false, false, false, false, 2)
                """,
                ownAircraftId.toString(), SEED_CLUB.toString(), SEED_CLUB.toString(),
                GLIDER_AIRCRAFT_TYPE.toString(),
                "HB-DLV" + Long.toString(ownAircraftId.getLeastSignificantBits() & 0xFFFF, 36));
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id, flight_aircraft_type_id,
                                    flight_date, is_solo_flight, no_start_time_information,
                                    no_ldg_time_information, process_state_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, CURRENT_DATE - 7,
                        false, false, false, ?::uuid)
                """,
                ownFlightId.toString(), SEED_CLUB.toString(), ownAircraftId.toString(),
                FLIGHT_PROCESS_STATE_VALID.toString());
    }

    @Test
    void orphanArticleNumberResolvesToNullKeepingTheLineNeverA23503() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.DELIVERY_ITEM);

        List<Map<String, Object>> rows = jdbc.queryForList(select);

        Map<String, Object> orphan = rows.stream()
                .filter(r -> r.get("deliveryitemid").toString().equals(orphanItemId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(orphan.get("resolvedarticleid"))
                .as("a DeliveryItem.ArticleNumber matching no LIVE article (only a "
                        + "soft-deleted one) resolves to NULL — the line is kept with a "
                        + "null article_id, never a fk_dli_article_id 23503 aborting the "
                        + "bundle; a LEFT->inner join would drop this row and an "
                        + "IsDeleted = 0 regression would resolve it onto the deleted article")
                .isNull();
        assertThat(orphan.get("articlenumber"))
                .as("the free-text ArticleNumber snapshot is preserved on the kept orphan line")
                .isEqualTo(orphanNumber);

        Map<String, Object> resolvable = rows.stream()
                .filter(r -> r.get("deliveryitemid").toString().equals(resolvableItemId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(resolvable.get("resolvedarticleid").toString())
                .as("a line whose ArticleNumber matches a live article resolves to that "
                        + "article's id (the LEFT JOIN still resolves the resolvable case)")
                .isEqualTo(liveArticleId.toString());
    }

    @Test
    void multipleDeliveriesShareOneFlightWithoutA23505UniqueViolation() {
        insertDeliveryOnFlight(dlvA);
        insertDeliveryOnFlight(dlvB);

        Integer onSharedFlight = jdbc.queryForObject(
                "SELECT count(*) FROM t_delivery WHERE flight_id = ?::uuid AND id IN (?::uuid, ?::uuid)",
                Integer.class, ownFlightId.toString(), dlvA.toString(), dlvB.toString());
        assertThat(onSharedFlight)
                .as("two deliveries share one flight_id with no 23505 — t_delivery must "
                        + "carry NO UNIQUE(flight_id) (legacy permits multiples; the write "
                        + "side's delete guard exists precisely because of it). A regression "
                        + "adding UNIQUE(flight_id) would 23505 on the second insert above")
                .isEqualTo(2);
    }

    private void insertItem(UUID id, int position, String articleNumber) {
        jdbc.update("""
                INSERT INTO DeliveryItems
                    (DeliveryItemId, DeliveryId, Position, ArticleNumber, ItemText,
                     AdditionalInformation, Quantity, DiscountInPercent, UnitType,
                     CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                     DeletedOn, DeletedByUserId)
                VALUES (?, ?, ?, ?, ?, NULL, ?, 0, ?, ?, NULL, NULL, NULL, NULL, NULL)
                """,
                id, deliveryId, position, articleNumber, "line " + position,
                new java.math.BigDecimal("1.000"), "Pcs",
                Timestamp.valueOf("2024-01-01 12:00:00"));
    }

    private void insertDeliveryOnFlight(UUID id) {
        jdbc.update("""
                INSERT INTO t_delivery
                    (id, operating_club_id, process_state_id, flight_id, batch_id,
                     created_on, modified_on)
                VALUES (?::uuid, ?::uuid, 10, ?::uuid, 0, now(), now())
                """,
                id.toString(), SEED_CLUB.toString(), ownFlightId.toString());
    }
}
