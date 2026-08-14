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

@Tag("slow")
class PersonFlightTimeCreditProducerDedupeIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private final UUID personId = UUID.randomUUID();

    private final UUID multiCurrentCreditId = UUID.randomUUID();
    private final UUID currentNewerTxId = UUID.randomUUID();
    private final UUID currentOlderTxId = UUID.randomUUID();
    private final UUID historicalTxId = UUID.randomUUID();

    private final UUID orphanDeliveryCreditId = UUID.randomUUID();
    private final UUID orphanTxId = UUID.randomUUID();
    private final UUID unmigratedDeliveryId = UUID.randomUUID();

    @BeforeEach
    void seedLegacyShapedStagingTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PersonFlightTimeCreditTransactions (
                    PersonFlightTimeCreditTransactionId  UUID PRIMARY KEY,
                    PersonFlightTimeCreditId             UUID NOT NULL,
                    BalancedDeliveryId                   UUID,
                    BalanceDateTime                      TIMESTAMP NOT NULL,
                    NoFlightTimeLimit                    BOOLEAN NOT NULL,
                    CurrentFlightTimeBalanceInSeconds    BIGINT,
                    FlightTimeBalanceInSeconds           BIGINT NOT NULL,
                    OldFlightTimeBalanceInSeconds        BIGINT,
                    IsCurrent                            SMALLINT NOT NULL,
                    CreatedOn                            TIMESTAMP NOT NULL,
                    CreatedByUserId                      UUID,
                    ModifiedOn                           TIMESTAMP,
                    ModifiedByUserId                     UUID,
                    DeletedOn                            TIMESTAMP,
                    DeletedByUserId                      UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS Deliveries (
                    DeliveryId UUID PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PersonFlightTimeCredits (
                    PersonFlightTimeCreditId             UUID PRIMARY KEY,
                    PersonId                             UUID NOT NULL,
                    NoFlightTimeLimit                    BOOLEAN NOT NULL,
                    ValidUntil                           TIMESTAMP NOT NULL,
                    UseRuleForAllAircraftsExceptListed   BOOLEAN NOT NULL,
                    MatchedAircraftImmatriculations      TEXT,
                    DiscountInPercent                    INTEGER NOT NULL,
                    CreatedOn                            TIMESTAMP NOT NULL,
                    CreatedByUserId                      UUID,
                    ModifiedOn                           TIMESTAMP,
                    ModifiedByUserId                     UUID,
                    DeletedOn                            TIMESTAMP,
                    DeletedByUserId                      UUID
                )
                """);
        jdbc.update("DELETE FROM PersonFlightTimeCreditTransactions");
        jdbc.update("DELETE FROM PersonFlightTimeCredits");
        jdbc.update("DELETE FROM Deliveries");

        insertCredit(multiCurrentCreditId);
        insertCredit(orphanDeliveryCreditId);

        insertTx(currentNewerTxId, multiCurrentCreditId, null,
                Timestamp.valueOf("2024-03-01 10:00:00"), 1, 7200L);
        insertTx(currentOlderTxId, multiCurrentCreditId, null,
                Timestamp.valueOf("2023-01-01 09:00:00"), 1, 3600L);
        insertTx(historicalTxId, multiCurrentCreditId, null,
                Timestamp.valueOf("2022-01-01 08:00:00"), 0, 1800L);

        insertTx(orphanTxId, orphanDeliveryCreditId, unmigratedDeliveryId,
                Timestamp.valueOf("2024-05-01 12:00:00"), 1, 5400L);
    }

    @AfterEach
    void dropStagingTables() {
        jdbc.execute("DROP TABLE IF EXISTS PersonFlightTimeCreditTransactions");
        jdbc.execute("DROP TABLE IF EXISTS PersonFlightTimeCredits");
        jdbc.execute("DROP TABLE IF EXISTS Deliveries");
    }

    @Test
    void producerSelectKeepsExactlyOneIsCurrentTransactionPerCreditMostRecentWins() {
        String select = MapperLegacyBindings.selectForProducer(
                EntityType.PERSON_FLIGHT_TIME_CREDIT_TRANSACTION);

        List<Map<String, Object>> rows = jdbc.queryForList(select);

        List<UUID> survivingForMultiCurrent = rows.stream()
                .filter(r -> r.get("personflighttimecreditid").toString()
                        .equals(multiCurrentCreditId.toString()))
                .map(r -> UUID.fromString(
                        r.get("personflighttimecredittransactionid").toString()))
                .toList();

        assertThat(survivingForMultiCurrent)
                .as("exactly one IsCurrent transaction survives per credit — the "
                        + "most-recent BalanceDateTime wins; the older current + the "
                        + "non-current transaction are dropped, else the 2nd current "
                        + "23505s on ux_pftc_transaction_current at ingest")
                .containsExactly(currentNewerTxId);
    }

    @Test
    void producerSelectNullsBalancedDeliveryIdWhenTheLinkedDeliveryIsNotMigrated() {
        String select = MapperLegacyBindings.selectForProducer(
                EntityType.PERSON_FLIGHT_TIME_CREDIT_TRANSACTION);

        Map<String, Object> orphanRow = jdbc.queryForList(select).stream()
                .filter(r -> r.get("personflighttimecredittransactionid").toString()
                        .equals(orphanTxId.toString()))
                .findFirst()
                .orElseThrow();

        assertThat(orphanRow.get("resolvedbalanceddeliveryid"))
                .as("BalancedDeliveryId is nulled when its Delivery is not migrated "
                        + "(J-10b deferred) — else fk_pftc_transaction_balanced_delivery_id "
                        + "23503s at ingest")
                .isNull();
        assertThat(orphanRow.get("personflighttimecreditid").toString())
                .as("the transaction keeps its intra-aggregate credit FK so the current "
                        + "balance round-trips for the engine")
                .isEqualTo(orphanDeliveryCreditId.toString());
    }

    @Test
    void creditProducerSelectCarriesThePersonIdForCrossTenantResolution() {
        String select = MapperLegacyBindings.selectForProducer(
                EntityType.PERSON_FLIGHT_TIME_CREDIT);

        Map<String, Object> creditRow = jdbc.queryForList(select).stream()
                .filter(r -> r.get("personflighttimecreditid").toString()
                        .equals(orphanDeliveryCreditId.toString()))
                .findFirst()
                .orElseThrow();

        assertThat(creditRow.get("personid").toString())
                .as("the credit carries its owning PersonId — the cross-tenant FK the "
                        + "rewriter resolves against the migrated Person")
                .isEqualTo(personId.toString());
    }

    @Test
    void personClubProducerSelectCarriesPersonClubAndNullsMemberState() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PersonClub (
                    PersonId                                UUID NOT NULL,
                    ClubId                                  UUID NOT NULL,
                    MemberNumber                            TEXT,
                    MemberStateId                           UUID,
                    IsMotorPilot                            BOOLEAN NOT NULL,
                    IsTowPilot                              BOOLEAN NOT NULL,
                    IsGliderInstructor                      BOOLEAN NOT NULL,
                    IsGliderPilot                           BOOLEAN NOT NULL,
                    IsGliderTrainee                         BOOLEAN NOT NULL,
                    IsPassenger                             BOOLEAN NOT NULL,
                    IsWinchOperator                         BOOLEAN NOT NULL,
                    IsMotorInstructor                       BOOLEAN NOT NULL,
                    ReceiveFlightReports                    BOOLEAN NOT NULL,
                    ReceiveAircraftReservationNotifications BOOLEAN NOT NULL,
                    ReceivePlanningDayRoleReminder          BOOLEAN NOT NULL,
                    IsActive                                BOOLEAN NOT NULL,
                    CreatedOn                               TIMESTAMP NOT NULL,
                    CreatedByUserId                         UUID,
                    ModifiedOn                              TIMESTAMP,
                    ModifiedByUserId                        UUID,
                    DeletedOn                               TIMESTAMP,
                    DeletedByUserId                         UUID,
                    PRIMARY KEY (PersonId, ClubId)
                )
                """);
        UUID clubId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO PersonClub
                        (PersonId, ClubId, MemberNumber, MemberStateId,
                         IsMotorPilot, IsTowPilot, IsGliderInstructor, IsGliderPilot,
                         IsGliderTrainee, IsPassenger, IsWinchOperator, IsMotorInstructor,
                         ReceiveFlightReports, ReceiveAircraftReservationNotifications,
                         ReceivePlanningDayRoleReminder, IsActive,
                         CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                         DeletedOn, DeletedByUserId)
                    VALUES (?, ?, '205207', ?,
                            FALSE, FALSE, FALSE, TRUE,
                            TRUE, FALSE, FALSE, FALSE,
                            TRUE, TRUE, TRUE, TRUE,
                            ?, NULL, NULL, NULL, NULL, NULL)
                    """,
                    personId, clubId, UUID.randomUUID(),
                    Timestamp.valueOf("2024-01-01 00:00:00"));

            String select = MapperLegacyBindings.selectForProducer(EntityType.PERSON_CLUB);
            Map<String, Object> row = jdbc.queryForList(select).stream()
                    .filter(r -> r.get("personid").toString().equals(personId.toString()))
                    .findFirst()
                    .orElseThrow();

            assertThat(row.get("clubid").toString())
                    .as("the membership carries its club_id — the indirect-tenancy anchor the "
                            + "credit-load pivot scopes on")
                    .isEqualTo(clubId.toString());
            assertThat(row.get("memberstateid"))
                    .as("member_state_id is nulled producer-side (MEMBER_STATE unmigrated) — "
                            + "else fk_person_club_member_state_id 23503s at ingest")
                    .isNull();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS PersonClub");
        }
    }

    private void insertCredit(UUID id) {
        jdbc.update("""
                INSERT INTO PersonFlightTimeCredits
                    (PersonFlightTimeCreditId, PersonId, NoFlightTimeLimit, ValidUntil,
                     UseRuleForAllAircraftsExceptListed, MatchedAircraftImmatriculations,
                     DiscountInPercent, CreatedOn, CreatedByUserId,
                     ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId)
                VALUES (?, ?, FALSE, ?, FALSE, ?, ?, ?, NULL, NULL, NULL, NULL, NULL)
                """,
                id, personId, Timestamp.valueOf("2030-12-31 00:00:00"),
                "HBABC", 25, Timestamp.valueOf("2024-01-01 00:00:00"));
    }

    private void insertTx(UUID id, UUID creditId, UUID balancedDeliveryId,
                          Timestamp balanceDateTime, int isCurrent, long balanceSeconds) {
        jdbc.update("""
                INSERT INTO PersonFlightTimeCreditTransactions
                    (PersonFlightTimeCreditTransactionId, PersonFlightTimeCreditId,
                     BalancedDeliveryId, BalanceDateTime, NoFlightTimeLimit,
                     CurrentFlightTimeBalanceInSeconds, FlightTimeBalanceInSeconds,
                     OldFlightTimeBalanceInSeconds, IsCurrent,
                     CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                     DeletedOn, DeletedByUserId)
                VALUES (?, ?, ?, ?, FALSE, ?, ?, NULL, ?, ?, NULL, NULL, NULL, NULL, NULL)
                """,
                id, creditId, balancedDeliveryId, balanceDateTime,
                balanceSeconds, balanceSeconds, isCurrent, balanceDateTime);
    }
}
