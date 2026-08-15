package ch.alpenflight.migrations.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

public final class AircraftOwnerLinkSeeder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AircraftOwnerLinkSeeder() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: AircraftOwnerLinkSeeder <aircraftId> "
                    + "<ownerKeycloakSub> <ownerClubId> <languageId>");
            System.exit(2);
            return;
        }
        UUID aircraftId = UUID.fromString(args[0]);
        UUID ownerSub = UUID.fromString(args[1]);
        UUID ownerClubId = UUID.fromString(args[2]);
        UUID languageId = UUID.fromString(args[3]);

        String url = envOrDefault("DATASOURCE_URL",
                "jdbc:postgresql://localhost:5432/alpenflight");
        String user = envOrDefault("DATASOURCE_USER", "alpenflight");
        String password = envOrDefault("DATASOURCE_PASSWORD", "alpenflight");

        UUID personId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_person (id, firstname, lastname) "
                            + "VALUES (?::uuid, ?, ?)")) {
                ps.setString(1, personId.toString());
                ps.setString(2, "S163");
                ps.setString(3, "OwnerPerson");
                ps.executeUpdate();
            }

            int existingJitUserRowsReboundToThePerson;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_user SET person_id = ?::uuid "
                            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL")) {
                ps.setString(1, personId.toString());
                ps.setString(2, ownerSub.toString());
                existingJitUserRowsReboundToThePerson = ps.executeUpdate();
            }
            if (existingJitUserRowsReboundToThePerson == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO t_user (id, club_id, username, friendly_name, person_id, "
                                + "notification_email, language_id, keycloak_sub) "
                                + "VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?::uuid, ?::uuid)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, ownerClubId.toString());
                    ps.setString(3, "S163-" + ownerSub);
                    ps.setString(4, "S163 Owner Person");
                    ps.setString(5, personId.toString());
                    ps.setString(6, "s163-owner@example.com");
                    ps.setString(7, languageId.toString());
                    ps.setString(8, ownerSub.toString());
                    ps.executeUpdate();
                }
            }

            int aircraftRows;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_aircraft SET aircraft_owner_person_id = ?::uuid "
                            + "WHERE id = ?::uuid")) {
                ps.setString(1, personId.toString());
                ps.setString(2, aircraftId.toString());
                aircraftRows = ps.executeUpdate();
            }
            if (aircraftRows != 1) {
                conn.rollback();
                throw new IllegalStateException(
                        "AircraftOwnerLinkSeeder: expected to update exactly 1 aircraft for id "
                                + aircraftId + ", updated " + aircraftRows
                                + " — the aircraft must exist before linking its owner-person");
            }

            conn.commit();
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("personId", personId.toString());
        System.out.println(JSON.writeValueAsString(result));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
