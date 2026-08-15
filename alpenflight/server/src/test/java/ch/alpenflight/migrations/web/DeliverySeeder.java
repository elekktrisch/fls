package ch.alpenflight.migrations.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class DeliverySeeder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final short PREPARED_SO_NO_DELIVERY_NUMBER_IS_ASSIGNED_YET = 10;

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
                            + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, deliveryId.toString());
                ps.setString(2, clubId.toString());
                ps.setShort(3, PREPARED_SO_NO_DELIVERY_NUMBER_IS_ASSIGNED_YET);
                ps.setString(4, flightId.toString());
                ps.setString(5, "Test");
                ps.setString(6, recipientLastName);
                ps.setString(7, "Flugplatzstrasse 1");
                ps.setString(8, "8000");
                ps.setString(9, "Zürich");
                ps.setString(10, "CH");
                ps.setString(11, "1234");
                ps.setLong(12, batchId);
                ps.setObject(13, now);
                ps.setObject(14, now);
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
