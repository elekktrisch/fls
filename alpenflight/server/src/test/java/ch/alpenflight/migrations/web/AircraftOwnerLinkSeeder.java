package ch.alpenflight.migrations.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * J-1 T-07 — DB-fixture seeder for the S-163 owner-person edit [edge] case in
 * the real-idp aircraft parity spec. NOT a mock and NOT a bypass of any seam
 * under test: it only sets up the DB STATE that the S-163 predicate then reads
 * LIVE (the access decision still runs fully real, through the real backend
 * reading real rows + the real Keycloak JWT). It exists because the
 * {@code aircraft_owner_person_id} column and a {@code t_user.person_id} link
 * have no UI or REST surface to set — exactly the rows
 * {@code AircraftsAuthorizationIT.ownerPerson_ofNonManagingClub_canEditAndDelete}
 * sets via raw SQL. This is the launchable equivalent against the live dev
 * Postgres, mirroring how the bundle seeders are the launchable seam over the
 * server-IT bundle factory.
 *
 * <p>It writes three things, idempotently:
 * <ol>
 *   <li>a {@code t_person} row (the owner Person),</li>
 *   <li>a {@code t_user} row binding the owner caller's Keycloak {@code sub} →
 *       that Person ({@code person_id}). If a JIT-materialized row already
 *       exists for the sub (null {@code person_id}), it is UPDATEd to carry the
 *       link rather than inserted (the {@code keycloak_sub} partial unique
 *       index forbids a duplicate),</li>
 *   <li>sets {@code t_aircraft.aircraft_owner_person_id} = that Person on the
 *       target aircraft.</li>
 * </ol>
 *
 * <p>The {@code S163-} username + firstname namespace keeps it sweepable and
 * distinct from JIT / migrated rows.
 *
 * <p>Args (positional):
 * {@code <aircraftId> <ownerKeycloakSub> <ownerClubId> <languageId>}.
 * {@code aircraftId} is the RAW uuid (strip the {@code ac-} external prefix).
 * Connects via {@code DATASOURCE_URL/USER/PASSWORD} (same env the Flyway +
 * backend steps use). Prints a single JSON line on stdout:
 * {@code {"personId":"…"}} which the spec parses for its post-run cleanup.
 */
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

            // (1) The owner Person.
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_person (id, firstname, lastname) "
                            + "VALUES (?::uuid, ?, ?)")) {
                ps.setString(1, personId.toString());
                ps.setString(2, "S163");
                ps.setString(3, "OwnerPerson");
                ps.executeUpdate();
            }

            // (2) Bind the owner caller's KC sub → that Person. A JIT row may
            //     already exist for the sub (from a prior login) carrying a null
            //     person_id; UPDATE it in place rather than colliding on the
            //     keycloak_sub partial unique. The UPDATE affects 0 rows when no
            //     JIT row exists yet (owner has not logged in), so fall back to
            //     INSERT in that case.
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_user SET person_id = ?::uuid "
                            + "WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL")) {
                ps.setString(1, personId.toString());
                ps.setString(2, ownerSub.toString());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
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

            // (3) Point the aircraft at the owner Person.
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
