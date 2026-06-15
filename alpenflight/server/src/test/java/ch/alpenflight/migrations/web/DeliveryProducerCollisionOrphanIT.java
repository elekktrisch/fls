package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.migration.bundle.accounting.DeliveryItemMapper;
import ch.alpenflight.migration.bundle.accounting.DeliveryMapper;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Delivery / DeliveryItem consumer-side collision/orphan proof.
 *
 * <p>The V4 {@code t_delivery} / {@code t_delivery_item} schema carries
 * constraints the legacy MSSQL schema lacks; this IT drives the REAL bound
 * consumer path ({@link DeliveryMapper#readEntity} / {@link DeliveryItemMapper}
 * over {@link MapperLegacyBindings#insertForConsumer}) against those tables and
 * asserts each constraint, so the failure is caught in {@code check} (minutes)
 * rather than at the ~20-min real fanout. The NDJSON is shaped exactly as the
 * producer emits AFTER the {@code ForeignKeyResolver} pass — FK columns carry
 * already-resolved new-stack UUIDs (or {@code null} where the producer nulled an
 * unmigrated recipient). Mirrors the round-trip-IT family
 * ({@code FlightMigrationRoundTripIT}, {@code AccountingRuleFilterProducerDedupeIT}).
 *
 * <p>The four cases (the binding's documented behavior):
 * <ul>
 *   <li><strong>delivery_number parse-collision → 23505.</strong> Two legacy
 *       delivery-number texts parsing to the SAME integer for one club collide
 *       on {@code ux_dlv_club_number_partial}. The producer does NOT silently
 *       dedupe (renumbering a Swiss OR Art. 957a legal invoice number would
 *       corrupt the legal record — the ArticleMapper hard-fail idiom), so the
 *       collision surfaces as {@code 23505}.</li>
 *   <li><strong>delivery_item.article_id RESTRICT orphan → 23503.</strong> The
 *       producer resolves the article by {@code (ClubId, ArticleNumber)}; an item
 *       whose article was not migrated leaves an unresolved FK that violates the
 *       {@code fk_dli_article_id} RESTRICT.</li>
 *   <li><strong>delivery.flight_id RESTRICT orphan → 23503.</strong> A delivery
 *       referencing an unmigrated flight violates {@code fk_dlv_flight_id}
 *       RESTRICT.</li>
 *   <li><strong>delivery.recipient_person_id SET NULL.</strong> The producer
 *       LEFT JOINs Persons and emits NULL when the recipient is not migrated, so
 *       the row INSERTs with a NULL recipient FK while the frozen 9-field
 *       recipient_* snapshot still renders (OR Art. 957a).</li>
 * </ul>
 */
@Tag("slow")
class DeliveryProducerCollisionOrphanIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH =
            UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE =
            UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_AIRCRAFT_TYPE_GLIDER =
            UUID.fromString("019e2e15-2c00-7af9-8000-000000002af9");
    private static final UUID SEED_PROCESS_STATE_VALID =
            UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");

    private static final String DELIVERY_INSERT =
            MapperLegacyBindings.insertForConsumer(EntityType.DELIVERY);
    private static final String DELIVERY_ITEM_INSERT =
            MapperLegacyBindings.insertForConsumer(EntityType.DELIVERY_ITEM);

    private final DeliveryMapper deliveryMapper = new DeliveryMapper();
    private final DeliveryItemMapper deliveryItemMapper = new DeliveryItemMapper();

    @Autowired DataSource dataSource;

    private UUID clubId;
    private UUID aircraftId;
    private UUID flightId;
    private UUID articleId;
    private UUID recipientPersonId;
    private String clubKey;

    @BeforeEach
    void seedFixtures() throws SQLException {
        clubId = UUID.randomUUID();
        aircraftId = UUID.randomUUID();
        flightId = UUID.randomUUID();
        articleId = UUID.randomUUID();
        recipientPersonId = UUID.randomUUID();
        clubKey = "DLV" + clubId.toString().substring(0, 4);
        try (Connection conn = dataSource.getConnection()) {
            insertClub(conn);
            insertAircraft(conn);
            insertFlight(conn);
            insertArticle(conn, "A-1001");
            insertPerson(conn);
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM t_delivery_item WHERE operating_club_id = ?", clubId);
            exec(conn, "DELETE FROM t_delivery WHERE operating_club_id = ?", clubId);
            exec(conn, "DELETE FROM t_article WHERE operating_club_id = ?", clubId);
            exec(conn, "DELETE FROM t_flight WHERE operating_club_id = ?", clubId);
            exec(conn, "DELETE FROM t_aircraft WHERE managing_club_id = ?", clubId);
            exec(conn, "DELETE FROM t_club WHERE id = ?", clubId);
            exec(conn, "DELETE FROM t_person WHERE id = ?", recipientPersonId);
        }
    }

    @Test
    void deliveryNumberParseCollisionViolatesClubNumberPartialUnique() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // "42" and "0042" are DISTINCT legacy texts parsing to the SAME integer
            // 42 for one club — the producer parses both, never deduping.
            insertDelivery(conn, deliveryRow(UUID.randomUUID(), 42, null, flightId,
                    recipientPersonId));

            Throwable collision = catchThrowable(() ->
                    insertDelivery(conn, deliveryRow(UUID.randomUUID(), 42, null, flightId,
                            recipientPersonId)));

            assertThat(sqlState(collision))
                    .as("two legacy delivery-number texts parsing to the same integer for one "
                            + "club collide on ux_dlv_club_number_partial — the producer does "
                            + "NOT renumber a legal invoice number (Swiss OR Art. 957a)")
                    .isEqualTo("23505");
        }
    }

    @Test
    void deliveryItemArticleOrphanViolatesArticleRestrict() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID deliveryId = UUID.randomUUID();
            insertDelivery(conn, deliveryRow(deliveryId, 100, null, flightId, recipientPersonId));

            UUID unmigratedArticleId = UUID.randomUUID();
            Throwable orphan = catchThrowable(() -> insertDeliveryItem(conn,
                    deliveryItemRow(UUID.randomUUID(), deliveryId, unmigratedArticleId, "A-9999")));

            assertThat(sqlState(orphan))
                    .as("a DeliveryItem whose (club, article-number) resolved to no migrated "
                            + "Article leaves an unresolved article_id that violates "
                            + "fk_dli_article_id RESTRICT — the orphan never silently drops "
                            + "(invoice integrity, OR Art. 957a)")
                    .isEqualTo("23503");
        }
    }

    @Test
    void deliveryFlightOrphanViolatesFlightRestrict() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID unmigratedFlightId = UUID.randomUUID();

            Throwable orphan = catchThrowable(() -> insertDelivery(conn,
                    deliveryRow(UUID.randomUUID(), 200, null, unmigratedFlightId,
                            recipientPersonId)));

            assertThat(sqlState(orphan))
                    .as("a Delivery for an unmigrated flight violates fk_dlv_flight_id RESTRICT")
                    .isEqualTo("23503");
        }
    }

    @Test
    void deliveryUnmigratedRecipientInsertsWithNullFkAndFrozenSnapshotSurvives()
            throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID deliveryId = UUID.randomUUID();
            // recipient_person_id null = the producer's LEFT-JOIN-Persons null when the
            // recipient is not in the export; the frozen recipient_* snapshot survives.
            insertDelivery(conn, deliveryRow(deliveryId, 300, null, flightId, null));

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT recipient_person_id, recipient_lastname, recipient_firstname, "
                            + "recipient_city FROM t_delivery WHERE id = ?")) {
                ps.setObject(1, deliveryId);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject("recipient_person_id"))
                            .as("an unmigrated recipient lands as a NULL FK (SET NULL FK never "
                                    + "fires on INSERT — the producer nulled it upstream)")
                            .isNull();
                    assertThat(rs.getString("recipient_lastname"))
                            .as("the frozen recipient_* snapshot still renders (OR Art. 957a)")
                            .isEqualTo("Frozen");
                    assertThat(rs.getString("recipient_firstname")).isEqualTo("Recipient");
                    assertThat(rs.getString("recipient_city")).isEqualTo("Zurich");
                }
            }
        }
    }

    // ---- NDJSON builders shaped as the producer emits (post-FK-resolve) ------

    private ObjectNode deliveryRow(UUID id, Integer deliveryNumber, String legacyNumberText,
            UUID resolvedFlightId, UUID resolvedRecipientPersonId) {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", id.toString());
        row.put("operating_club_id", clubId.toString());
        row.put("process_state_id", 10);
        putUuidOrNull(row, "flight_id", resolvedFlightId);
        putUuidOrNull(row, "recipient_person_id", resolvedRecipientPersonId);
        row.put("recipient_name", "Frozen Recipient");
        row.put("recipient_firstname", "Recipient");
        row.put("recipient_lastname", "Frozen");
        row.put("recipient_address_line1", "Hauptstrasse 1");
        row.putNull("recipient_address_line2");
        row.put("recipient_zip_code", "8000");
        row.put("recipient_city", "Zurich");
        row.put("recipient_country_name", "Switzerland");
        row.put("recipient_person_club_member_number", "1001");
        row.put("delivery_information", "Pickup at front desk");
        row.putNull("additional_information");
        putIntOrNull(row, "delivery_number", deliveryNumber);
        putStringOrNull(row, "legacy_delivery_number_text", legacyNumberText);
        row.put("delivered_on", Instant.parse("2024-06-15T18:30:00Z").toString());
        row.put("batch_id", 0L);
        row.put("created_on", Instant.parse("2024-01-01T12:00:00Z").toString());
        row.putNull("created_by_user_id");
        row.put("modified_on", Instant.parse("2024-01-01T12:00:00Z").toString());
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return row;
    }

    private ObjectNode deliveryItemRow(UUID id, UUID deliveryId, UUID resolvedArticleId,
            String articleNumber) {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", id.toString());
        row.put("operating_club_id", clubId.toString());
        row.put("delivery_id", deliveryId.toString());
        row.put("position", 1);
        row.put("article_id", resolvedArticleId.toString());
        row.put("article_number", articleNumber);
        row.put("item_text", "Flight time charge");
        row.putNull("additional_information");
        row.put("quantity", new java.math.BigDecimal("1.5000"));
        row.put("unit_price", new java.math.BigDecimal("0.0000"));
        row.put("discount_in_percent", 0);
        row.put("unit_type_code", "MINUTES");
        row.put("created_on", Instant.parse("2024-01-01T12:00:00Z").toString());
        row.putNull("created_by_user_id");
        row.put("modified_on", Instant.parse("2024-01-01T12:00:00Z").toString());
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return row;
    }

    private void insertDelivery(Connection conn, ObjectNode row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELIVERY_INSERT)) {
            deliveryMapper.readEntity(row, ps);
            ps.executeUpdate();
        }
    }

    private void insertDeliveryItem(Connection conn, ObjectNode row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELIVERY_ITEM_INSERT)) {
            deliveryItemMapper.readEntity(row, ps);
            ps.executeUpdate();
        }
    }

    // ---- minimal real-FK fixtures -------------------------------------------

    private void insertClub(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, clubId);
            ps.setString(2, "Delivery Orphan IT");
            ps.setString(3, clubKey);
            ps.setObject(4, SEED_COUNTRY_CH);
            ps.setObject(5, SEED_CLUB_STATE_ACTIVE);
            ps.executeUpdate();
        }
    }

    private void insertAircraft(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_aircraft (id, managing_club_id, aircraft_type_id, "
                        + "immatriculation) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, aircraftId);
            ps.setObject(2, clubId);
            ps.setObject(3, SEED_AIRCRAFT_TYPE_GLIDER);
            ps.setString(4, ("HB-" + clubId.toString().substring(0, 5))
                    .toUpperCase(java.util.Locale.ROOT));
            ps.executeUpdate();
        }
    }

    private void insertFlight(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_flight (id, operating_club_id, aircraft_id, "
                        + "flight_aircraft_type_id, flight_date, is_solo_flight, "
                        + "no_start_time_information, no_ldg_time_information, "
                        + "process_state_id, version) "
                        + "VALUES (?, ?, ?, 1, DATE '2024-06-15', false, false, false, ?, 0)")) {
            ps.setObject(1, flightId);
            ps.setObject(2, clubId);
            ps.setObject(3, aircraftId);
            ps.setObject(4, SEED_PROCESS_STATE_VALID);
            ps.executeUpdate();
        }
    }

    private void insertArticle(Connection conn, String articleNumber) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_article (id, operating_club_id, article_number, article_name, "
                        + "is_active) VALUES (?, ?, ?, ?, true)")) {
            ps.setObject(1, articleId);
            ps.setObject(2, clubId);
            ps.setString(3, articleNumber);
            ps.setString(4, "Flight time");
            ps.executeUpdate();
        }
    }

    private void insertPerson(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_person (id, firstname, lastname) VALUES (?, ?, ?)")) {
            ps.setObject(1, recipientPersonId);
            ps.setString(2, "Recipient");
            ps.setString(3, "Frozen");
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql, UUID arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, arg);
            ps.executeUpdate();
        }
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("expected a SQLException with a SQLSTATE", thrown);
    }

    private static void putUuidOrNull(ObjectNode row, String field, UUID value) {
        if (value == null) {
            row.putNull(field);
        } else {
            row.put(field, value.toString());
        }
    }

    private static void putIntOrNull(ObjectNode row, String field, Integer value) {
        if (value == null) {
            row.putNull(field);
        } else {
            row.put(field, value.intValue());
        }
    }

    private static void putStringOrNull(ObjectNode row, String field, String value) {
        if (value == null) {
            row.putNull(field);
        } else {
            row.put(field, value);
        }
    }
}
