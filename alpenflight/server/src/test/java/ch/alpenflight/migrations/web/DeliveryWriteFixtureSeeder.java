package ch.alpenflight.migrations.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class DeliveryWriteFixtureSeeder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID LOCKED_PROCESS_STATE =
            UUID.fromString("019e2e15-2c00-7a9b-8000-000000003a9b");
    private static final UUID DELIVERY_PREPARED_PROCESS_STATE =
            UUID.fromString("019e2e15-2c00-7a9d-8000-000000003a9d");
    private static final UUID VALID_PROCESS_STATE =
            UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");
    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final short PREPARED = 10;
    private static final int DEFAULT_AGED_DAYS_CLEARING_THE_CREATE_ELIGIBILITY_WINDOW = 4;

    private DeliveryWriteFixtureSeeder() { }

    private record Item(int position, String itemText, String quantity, String unitType) { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "lock-and-age" -> lockAndAge(
                    UUID.fromString(args[1]),
                    args.length > 2 ? Integer.parseInt(args[2])
                            : DEFAULT_AGED_DAYS_CLEARING_THE_CREATE_ELIGIBILITY_WINDOW);
            case "prepare-flight" ->
                    prepareFlightSoTheBookingWriteIsALegalTransition(UUID.fromString(args[1]));
            case "reset-flight" ->
                    resetFlightSoTheNextCreateBatchCannotReprocessIt(UUID.fromString(args[1]));
            case "link-tow" -> linkTow(UUID.fromString(args[1]), UUID.fromString(args[2]));
            case "rule-filter" -> ruleFilter(UUID.fromString(args[1]), args[2]);
            case "delivery" -> delivery(
                    UUID.fromString(args[1]),
                    "-".equals(args[2]) ? null : UUID.fromString(args[2]),
                    args[3],
                    Long.parseLong(args[4]));
            case "delete-delivery" -> deleteDelivery(UUID.fromString(args[1]));
            case "delete-filter" -> deleteFilter(UUID.fromString(args[1]));
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage: DeliveryWriteFixtureSeeder <lock-and-age|prepare-flight|"
                + "reset-flight|link-tow|rule-filter|delivery|delete-delivery|delete-filter> <args...>");
        System.exit(2);
    }

    private static void lockAndAge(UUID flightId, int agedDays) throws Exception {
        OffsetDateTime aged = OffsetDateTime.now(ZoneOffset.UTC).minusDays(agedDays);
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_flight SET process_state_id = ?::uuid, created_on = ?, "
                            + "modified_on = ? WHERE id = ?::uuid")) {
                ps.setString(1, LOCKED_PROCESS_STATE.toString());
                ps.setObject(2, aged);
                ps.setObject(3, OffsetDateTime.now(ZoneOffset.UTC));
                ps.setString(4, flightId.toString());
                ps.executeUpdate();
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("flightId", flightId.toString());
        result.put("processState", "LOCKED");
        System.out.println(JSON.writeValueAsString(result));
    }

    private static void prepareFlightSoTheBookingWriteIsALegalTransition(UUID flightId)
            throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_flight SET process_state_id = ?::uuid, modified_on = ? "
                            + "WHERE id = ?::uuid")) {
                ps.setString(1, DELIVERY_PREPARED_PROCESS_STATE.toString());
                ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
                ps.setString(3, flightId.toString());
                ps.executeUpdate();
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("flightId", flightId.toString());
        result.put("processState", "DELIVERY_PREPARED");
        System.out.println(JSON.writeValueAsString(result));
    }

    private static void resetFlightSoTheNextCreateBatchCannotReprocessIt(UUID flightId)
            throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_flight SET process_state_id = ?::uuid, tow_flight_id = NULL, "
                            + "modified_on = ? WHERE id = ?::uuid")) {
                ps.setString(1, VALID_PROCESS_STATE.toString());
                ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
                ps.setString(3, flightId.toString());
                ps.executeUpdate();
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("flightId", flightId.toString());
        result.put("processState", "VALID");
        System.out.println(JSON.writeValueAsString(result));
    }

    private static void linkTow(UUID gliderFlightId, UUID towFlightId) throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_flight SET tow_flight_id = ?::uuid, modified_on = ? "
                            + "WHERE id = ?::uuid")) {
                ps.setString(1, towFlightId.toString());
                ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
                ps.setString(3, gliderFlightId.toString());
                ps.executeUpdate();
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("gliderFlightId", gliderFlightId.toString());
        result.put("towFlightId", towFlightId.toString());
        System.out.println(JSON.writeValueAsString(result));
    }

    private static void ruleFilter(UUID clubId, String articleNumber) throws Exception {
        String filterName = "Delivery write fixture FlightTime " + articleNumber;
        UUID filterId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String filterConfigJsonWhoseKeysMustAllBeFilterConfigComponents =
                "{\"isRuleForGliderFlights\":true,\"deliveryLineText\":\"Flight time\"}";
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            ensureTheRuleTargetArticleExistsElseTheCreatePathErrors(
                    conn, clubId, articleNumber, now);
            UUID resolved = resolveFilterId(conn, clubId, filterName);
            if (resolved == null) {
                int sort = nextSortIndicator(conn, clubId);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO t_accounting_rule_filter (id, operating_club_id, filter_type_id, "
                                + "accounting_unit_type_id, rule_filter_name, is_active, sort_indicator, "
                                + "article_target, filter_config, created_on, modified_on) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, true, ?, ?, ?::jsonb, ?, ?)")) {
                    ps.setString(1, filterId.toString());
                    ps.setString(2, clubId.toString());
                    ps.setString(3, FILTER_TYPE_FLIGHT_TIME.toString());
                    ps.setString(4, UNIT_MINUTES.toString());
                    ps.setString(5, filterName);
                    ps.setInt(6, sort);
                    ps.setString(7, articleNumber);
                    ps.setString(8, filterConfigJsonWhoseKeysMustAllBeFilterConfigComponents);
                    ps.setObject(9, now);
                    ps.setObject(10, now);
                    ps.executeUpdate();
                }
                resolved = filterId;
            }
            conn.commit();
            ObjectNode result = JSON.createObjectNode();
            result.put("filterId", resolved.toString());
            System.out.println(JSON.writeValueAsString(result));
        }
    }

    private static void delivery(UUID clubId, UUID flightId, String recipientLastName, long batchId)
            throws Exception {
        UUID deliveryId = UUID.randomUUID();
        String articleNumber = "DLW-SEED-" + Long.toString(batchId, 36);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Item[] items = {
            new Item(1, "Flight time tier 1", "1800", "Sec"),
            new Item(2, "Flight time tier 2", "1800", "Sec"),
            new Item(3, "Landing tax", "1", "Pcs"),
        };

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            UUID articleId = upsertArticle(conn, clubId, articleNumber, now);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_delivery (id, operating_club_id, process_state_id, flight_id, "
                            + "recipient_firstname, recipient_lastname, recipient_address_line1, "
                            + "recipient_zip_code, recipient_city, recipient_country_name, "
                            + "recipient_person_club_member_number, batch_id, created_on, modified_on) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, deliveryId.toString());
                ps.setString(2, clubId.toString());
                ps.setShort(3, PREPARED);
                if (flightId == null) {
                    ps.setNull(4, java.sql.Types.OTHER);
                } else {
                    ps.setString(4, flightId.toString());
                }
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
                    ps.setString(5, articleId.toString());
                    ps.setString(6, articleNumber);
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
        try (Connection conn = connect()) {
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

    private static void deleteFilter(UUID filterId) throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM t_accounting_rule_filter WHERE id = ?::uuid")) {
                ps.setString(1, filterId.toString());
                ps.executeUpdate();
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("deletedFilterId", filterId.toString());
        System.out.println(JSON.writeValueAsString(result));
    }

    private static void ensureTheRuleTargetArticleExistsElseTheCreatePathErrors(
            Connection conn, UUID clubId, String articleNumber, OffsetDateTime now)
            throws Exception {
        upsertArticle(conn, clubId, articleNumber, now);
    }

    private static UUID resolveFilterId(Connection conn, UUID clubId, String filterName)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM t_accounting_rule_filter WHERE operating_club_id = ?::uuid "
                        + "AND rule_filter_name = ? AND deleted_on IS NULL")) {
            ps.setString(1, clubId.toString());
            ps.setString(2, filterName);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    private static int nextSortIndicator(Connection conn, UUID clubId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(sort_indicator), -1) + 1 FROM t_accounting_rule_filter "
                        + "WHERE operating_club_id = ?::uuid AND deleted_on IS NULL")) {
            ps.setString(1, clubId.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static UUID upsertArticle(Connection conn, UUID clubId, String articleNumber,
                                      OffsetDateTime now) throws Exception {
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
        UUID articleId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_article (id, operating_club_id, article_number, article_name, "
                        + "is_active, created_on, modified_on) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, true, ?, ?)")) {
            ps.setString(1, articleId.toString());
            ps.setString(2, clubId.toString());
            ps.setString(3, articleNumber);
            ps.setString(4, "Delivery write fixture article");
            ps.setObject(5, now);
            ps.setObject(6, now);
            ps.executeUpdate();
        }
        return articleId;
    }

    private static Connection connect() throws Exception {
        String url = envOrDefault("DATASOURCE_URL", "jdbc:postgresql://localhost:5432/alpenflight");
        String user = envOrDefault("DATASOURCE_USER", "alpenflight");
        String password = envOrDefault("DATASOURCE_PASSWORD", "alpenflight");
        return DriverManager.getConnection(url, user, password);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
