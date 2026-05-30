package ch.alpenflight.migration.bundle.parity;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Minimal legacy FLSTest schema applied to the MSSQL container — only the
 * tables the vertical-slice harness reads. The full FLSTest schema (62+
 * DBUpdate scripts) is what S-187a will apply once the remaining 25 mappers
 * land; carrying it now would multiply container start time and add a
 * dependency on the legacy MSSQL DDL dialect that has nothing to do with
 * proving the mapper round-trip plumbing.
 *
 * <p>Column shapes match the {@code SELECT}s in the three mappers under
 * test ({@code CountryMapper}, {@code ClubMapper}, {@code UserMapper}).
 * Identifiers are MSSQL {@code UNIQUEIDENTIFIER}; timestamps are
 * {@code DATETIME2(7)} — ADR 0019 calls for legacy GUID preservation, so the
 * GUID columns flow through to Postgres unchanged.
 */
public final class LegacyTestSchema {

    private static final List<String> DDL = List.of(
            // Countries — SYSTEM_GLOBAL reference; bundle carries (legacy_guid, iso2_code).
            """
            CREATE TABLE Countries (
              CountryId UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
              CountryCodeIso2 NVARCHAR(2) NOT NULL
            )
            """,
            // Clubs — tenant root. Cardinality and column shape match the
            // V2 t_club destination via ClubMapper.writeNdjson.
            """
            CREATE TABLE Clubs (
              ClubId UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
              Clubname NVARCHAR(255) NOT NULL,
              ClubKey NVARCHAR(50) NOT NULL,
              Address NVARCHAR(255) NULL,
              Zip NVARCHAR(20) NULL,
              City NVARCHAR(100) NULL,
              CountryId UNIQUEIDENTIFIER NOT NULL,
              Phone NVARCHAR(50) NULL,
              FaxNumber NVARCHAR(50) NULL,
              Email NVARCHAR(255) NULL,
              WebPage NVARCHAR(255) NULL,
              Contact NVARCHAR(255) NULL,
              ClubStateId INT NOT NULL,
              SendAircraftStatisticReportTo NVARCHAR(255) NULL,
              SendPlanningDayInfoMailTo NVARCHAR(255) NULL,
              SendDeliveryMailExportTo NVARCHAR(255) NULL,
              SendTrialFlightRegistrationOperatorEmailTo NVARCHAR(255) NULL,
              SendPassengerFlightRegistrationOperatorEmailTo NVARCHAR(255) NULL,
              ReplyToEmailAddress NVARCHAR(255) NULL,
              RunDeliveryCreationJob BIT NOT NULL,
              RunDeliveryMailExportJob BIT NOT NULL,
              LastPersonSynchronisationOn DATETIME2(7) NULL,
              LastDeliverySynchronisationOn DATETIME2(7) NULL,
              LastArticleSynchronisationOn DATETIME2(7) NULL,
              IsClubMemberNumberReadonly BIT NOT NULL,
              CreatedOn DATETIME2(7) NOT NULL,
              CreatedByUserId UNIQUEIDENTIFIER NOT NULL,
              ModifiedOn DATETIME2(7) NULL,
              ModifiedByUserId UNIQUEIDENTIFIER NULL,
              DeletedOn DATETIME2(7) NULL,
              DeletedByUserId UNIQUEIDENTIFIER NULL
            )
            """,
            // Users — tenant-bound principal. Password / Keycloak-shadow columns
            // intentionally absent (UserMapper.FORBIDDEN_LEGACY_COLUMNS deny-list
            // — defense-in-depth at the schema boundary too).
            """
            CREATE TABLE Users (
              UserId UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
              ClubId UNIQUEIDENTIFIER NOT NULL,
              UserName NVARCHAR(255) NOT NULL,
              FriendlyName NVARCHAR(255) NOT NULL,
              PersonId UNIQUEIDENTIFIER NULL,
              NotificationEmail NVARCHAR(255) NOT NULL,
              PhoneNumber NVARCHAR(50) NULL,
              Remarks NVARCHAR(MAX) NULL,
              LanguageId INT NOT NULL,
              CreatedOn DATETIME2(7) NOT NULL,
              CreatedByUserId UNIQUEIDENTIFIER NOT NULL,
              ModifiedOn DATETIME2(7) NULL,
              ModifiedByUserId UNIQUEIDENTIFIER NULL,
              DeletedOn DATETIME2(7) NULL,
              DeletedByUserId UNIQUEIDENTIFIER NULL
            )
            """);

    private LegacyTestSchema() { }

    public static void apply(Connection legacyConnection) throws SQLException {
        legacyConnection.setAutoCommit(true);
        try (Statement statement = legacyConnection.createStatement()) {
            for (String createStatement : DDL) {
                statement.execute(createStatement);
            }
        }
    }
}
