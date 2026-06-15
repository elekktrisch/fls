package ch.alpenflight.migrations.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * DB-fixture seeder for the clean-seed half of the real-idp deliveries parity
 * spec. The Delivery write side is deferred — there is no create REST surface
 * this iteration — so the read screen's clean-seed input is materialized directly
 * against the live dev Postgres, exactly as the engine-persist path will write it
 * later. The read endpoint + the {@code @TenantId} scope + the cross-tenant 404
 * still run fully real off these rows.
 *
 * <p>Writes, in one transaction, under {@code operatingClubId}:
 * <ol>
 *   <li>an active {@code t_article} the line items reference (the
 *       {@code delivery_item.article_id} FK is RESTRICT — a real article must
 *       exist), idempotent on (club, number),</li>
 *   <li>a {@code t_delivery} in process-state 10 (Prepared) with the nine frozen
 *       OR Art. 957a recipient fields + the linked flight,</li>
 *   <li>three {@code t_delivery_item} rows (two flight-time tiers + a landing
 *       tax) — engine-shaped output.</li>
 * </ol>
 *
 * <p>Args (positional):
 * {@code <operatingClubId> <flightId> <recipientLastName> <batchId>}, OR the
 * cleanup form {@code delete <deliveryId>} which removes the delivery + its
 * items (the read-only iteration has no delete REST surface, so a retry's
 * pre-clean rides the same seam). {@code flightId} is the RAW uuid (strip the
 * {@code fl-} external prefix). Connects via {@code DATASOURCE_URL/USER/PASSWORD}.
 * Prints one JSON line on stdout: {@code {"deliveryId":"…","articleNumber":"…"}}
 * for the spec's assertions + post-run cleanup.
 */
public final class DeliverySeeder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DeliverySeeder() { }

    private record Item(int position, String articleNumber, String itemText, String quantity,
                        String unitType) { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "delete".equals(args[0])) {
            deleteDelivery(UUID.fromString(args[1]));
            return;
        }
        if (args.length != 4) {
            System.err.println("usage: DeliverySeeder <operatingClubId> <flightId> "
                    + "<recipientLastName> <batchId> | delete <deliveryId>");
            System.exit(2);
            return;
        }
        UUID clubId = UUID.fromString(args[0]);
        UUID flightId = UUID.fromString(args[1]);
        String recipientLastName = args[2];
        long batchId = Long.parseLong(args[3]);

        String url = envOrDefault("DATASOURCE_URL", "jdbc:postgresql://localhost:5432/alpenflight");
        String user = envOrDefault("DATASOURCE_USER", "alpenflight");
        String password = envOrDefault("DATASOURCE_PASSWORD", "alpenflight");

        UUID deliveryId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        // The seeded delivery is Prepared (process-state 10) → delivery_number is
        // null until booked, so the list renders the recipient/batch/state
        // without a number. The view's load-bearing data is the line items + the
        // frozen recipient + the flight link, all asserted below.
        String articleNumber = "DLV-SEED-" + Long.toString(batchId, 36);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Item[] items = {
            new Item(1, articleNumber, "Flight time tier 1", "1800", "Sec"),
            new Item(2, articleNumber, "Flight time tier 2", "1800", "Sec"),
            new Item(3, articleNumber, "Landing tax", "1", "Pcs"),
        };

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            UUID resolvedArticleId = upsertArticle(conn, articleId, clubId, articleNumber, now);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_delivery (id, operating_club_id, process_state_id, flight_id, "
                            + "recipient_firstname, recipient_lastname, recipient_address_line1, "
                            + "recipient_zip_code, recipient_city, recipient_country_name, "
                            + "recipient_person_club_member_number, batch_id, created_on, modified_on) "
                            + "VALUES (?::uuid, ?::uuid, 10, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, deliveryId.toString());
                ps.setString(2, clubId.toString());
                ps.setString(3, flightId.toString());
                ps.setString(4, "Test");
                ps.setString(5, recipientLastName);
                ps.setString(6, "Flugplatzstrasse 1");
                ps.setString(7, "8000");
                ps.setString(8, "Zürich");
                ps.setString(9, "CH");
                ps.setString(10, "1234");
                ps.setLong(11, batchId);
                ps.setObject(12, now);
                ps.setObject(13, now);
                ps.executeUpdate();
            }

            for (Item item : items) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO t_delivery_item (id, operating_club_id, delivery_id, position, "
                                + "article_id, article_number, item_text, quantity, unit_price, "
                                + "discount_in_percent, unit_type_code, created_on, modified_on) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?::numeric, "
                                + "0, 0, ?, ?, ?)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, clubId.toString());
                    ps.setString(3, deliveryId.toString());
                    ps.setInt(4, item.position());
                    ps.setString(5, resolvedArticleId.toString());
                    ps.setString(6, item.articleNumber());
                    ps.setString(7, item.itemText());
                    ps.setString(8, item.quantity());
                    ps.setString(9, item.unitType());
                    ps.setObject(10, now);
                    ps.setObject(11, now);
                    ps.executeUpdate();
                }
            }

            conn.commit();
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("deliveryId", deliveryId.toString());
        result.put("articleNumber", articleNumber);
        System.out.println(JSON.writeValueAsString(result));
    }

    /** Remove a seeded delivery + its items (retry pre-clean / afterAll cleanup). */
    private static void deleteDelivery(UUID deliveryId) throws Exception {
        String url = envOrDefault("DATASOURCE_URL", "jdbc:postgresql://localhost:5432/alpenflight");
        String user = envOrDefault("DATASOURCE_USER", "alpenflight");
        String password = envOrDefault("DATASOURCE_PASSWORD", "alpenflight");
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM t_delivery_item WHERE delivery_id = ?::uuid")) {
                ps.setString(1, deliveryId.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM t_delivery WHERE id = ?::uuid")) {
                ps.setString(1, deliveryId.toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("deletedDeliveryId", deliveryId.toString());
        System.out.println(JSON.writeValueAsString(result));
    }

    /**
     * Insert the article the line items reference, or resolve the existing one on
     * (club, number) — a re-run reuses the row rather than colliding on the
     * identity-bearing index.
     */
    private static UUID upsertArticle(Connection conn, UUID articleId, UUID clubId,
                                      String articleNumber, OffsetDateTime now) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM t_article WHERE operating_club_id = ?::uuid "
                        + "AND article_number = ? AND deleted_on IS NULL")) {
            ps.setString(1, clubId.toString());
            ps.setString(2, articleNumber);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_article (id, operating_club_id, article_number, article_name, "
                        + "is_active, created_on, modified_on) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, true, ?, ?)")) {
            ps.setString(1, articleId.toString());
            ps.setString(2, clubId.toString());
            ps.setString(3, articleNumber);
            ps.setString(4, "Delivery seed article");
            ps.setObject(5, now);
            ps.setObject(6, now);
            ps.executeUpdate();
        }
        return articleId;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
