package ch.alpenflight.migration.bundle.parity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.datafaker.Faker;
import org.jspecify.annotations.Nullable;

public final class LegacyFixtureSeeder {

    public static final int CLUB_COUNT = 2;
    public static final int USERS_PER_CLUB = 3;

    private static final String SWITZERLAND_ISO2 = "CH";
    private static final String GERMAN_LANGUAGE_KEY = "de";

    private static final int ACTIVE_CLUB_STATE_LEGACY_ID = 1;

    private final long seed;
    private final Faker faker;

    public LegacyFixtureSeeder(long seed) {
        this.seed = seed;
        this.faker = new Faker(new Random(seed));
    }

    public long seed() {
        return seed;
    }

    public SeededFixture seedInto(Connection legacyConnection) throws SQLException {
        UUID switzerlandCountryId = lookupCountryId(legacyConnection, SWITZERLAND_ISO2);
        int germanLanguageId = lookupLanguageId(legacyConnection, GERMAN_LANGUAGE_KEY);

        legacyConnection.setAutoCommit(false);
        try {
            List<LegacyClub> clubs = seedClubs(
                    legacyConnection, switzerlandCountryId, ACTIVE_CLUB_STATE_LEGACY_ID);
            List<LegacyUser> users = seedUsers(legacyConnection, clubs, germanLanguageId);
            legacyConnection.commit();
            return new SeededFixture(
                    switzerlandCountryId, germanLanguageId, ACTIVE_CLUB_STATE_LEGACY_ID,
                    clubs, users);
        } catch (SQLException failure) {
            legacyConnection.rollback();
            throw failure;
        }
    }

    private static UUID lookupCountryId(Connection connection, String iso2)
            throws SQLException {
        String sql = "SELECT TOP 1 CountryId FROM Countries WHERE CountryCodeIso2 = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, iso2);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                            "Canonical FLSTest seed has no Country with CountryCodeIso2 = "
                                    + iso2 + " — schema applier ran without the "
                                    + "'3 Insert Static Data.sql' batch?");
                }
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static int lookupLanguageId(Connection connection, String key) throws SQLException {
        String sql = "SELECT TOP 1 LanguageId FROM Languages WHERE LOWER(LanguageKey) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                            "Canonical FLSTest seed has no Language with LanguageKey = "
                                    + key + " — schema applier did not apply the static "
                                    + "data batch.");
                }
                return rs.getInt(1);
            }
        }
    }

    private List<LegacyClub> seedClubs(
            Connection connection, UUID countryId, int clubStateId) throws SQLException {
        Timestamp createdOn = utcMicros(Instant.parse("2024-01-01T00:00:00Z"));
        List<LegacyClub> clubs = new ArrayList<>(CLUB_COUNT);
        String sql = """
                INSERT INTO Clubs (
                  ClubId, Clubname, ClubKey, Address, Zip, City, CountryId,
                  Phone, FaxNumber, Email, WebPage, Contact, ClubStateId,
                  SendAircraftStatisticReportTo, SendPlanningDayInfoMailTo,
                  SendDeliveryMailExportTo,
                  SendTrialFlightRegistrationOperatorEmailTo,
                  SendPassengerFlightRegistrationOperatorEmailTo,
                  ReplyToEmailAddress,
                  RunDeliveryCreationJob, RunDeliveryMailExportJob,
                  LastPersonSynchronisationOn, LastDeliverySynchronisationOn,
                  LastArticleSynchronisationOn,
                  IsClubMemberNumberReadonly,
                  CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                  DeletedOn, DeletedByUserId,
                  RecordState, OwnerId, OwnershipType, IsDeleted)
                VALUES (?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        1, ?, 2, 0)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < CLUB_COUNT; i++) {
                LegacyClub club = synthesizeClub(countryId, createdOn);
                int position = 1;
                ps.setString(position++, club.clubId().toString());
                ps.setString(position++, club.clubname());
                ps.setString(position++, club.clubKey());
                ps.setString(position++, club.address());
                ps.setString(position++, club.zip());
                ps.setString(position++, club.city());
                ps.setString(position++, club.countryId().toString());
                ps.setString(position++, club.phone());
                ps.setString(position++, club.faxNumber());
                ps.setString(position++, club.email());
                ps.setString(position++, club.webPage());
                ps.setString(position++, club.contact());
                ps.setInt(position++, clubStateId);
                ps.setString(position++, club.sendAircraftStatisticReportTo());
                ps.setString(position++, club.sendPlanningDayInfoMailTo());
                ps.setString(position++, club.sendDeliveryMailExportTo());
                ps.setString(position++, club.sendTrialFlightRegistrationOperatorEmail());
                ps.setString(position++, club.sendPassengerFlightRegistrationOperatorEmail());
                ps.setString(position++, club.replyToEmailAddress());
                ps.setBoolean(position++, true);
                ps.setBoolean(position++, true);
                ps.setNull(position++, java.sql.Types.TIMESTAMP);
                ps.setNull(position++, java.sql.Types.TIMESTAMP);
                ps.setNull(position++, java.sql.Types.TIMESTAMP);
                ps.setBoolean(position++, false);
                ps.setTimestamp(position++, club.createdOn());
                ps.setString(position++, club.createdByUserId().toString());
                ps.setNull(position++, java.sql.Types.TIMESTAMP);
                ps.setNull(position++, java.sql.Types.VARCHAR);
                ps.setNull(position++, java.sql.Types.TIMESTAMP);
                ps.setNull(position++, java.sql.Types.VARCHAR);
                ps.setString(position, club.createdByUserId().toString());
                ps.executeUpdate();
                clubs.add(club);
            }
        }
        return clubs;
    }

    private List<LegacyUser> seedUsers(
            Connection connection, List<LegacyClub> clubs, int languageId) throws SQLException {
        Timestamp createdOn = utcMicros(Instant.parse("2024-01-02T00:00:00Z"));
        List<LegacyUser> users = new ArrayList<>(clubs.size() * USERS_PER_CLUB);
        String sql = """
                INSERT INTO Users (
                  UserId, ClubId, UserName, FriendlyName, PersonId,
                  NotificationEmail, PhoneNumber, Remarks, LanguageId,
                  CreatedOn, CreatedByUserId,
                  ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId,
                  RecordState, OwnerId, OwnershipType, IsDeleted)
                VALUES (?, ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?,
                        ?, ?, ?, ?,
                        1, ?, 2, 0)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (LegacyClub club : clubs) {
                for (int u = 0; u < USERS_PER_CLUB; u++) {
                    LegacyUser user = synthesizeUser(club, createdOn);
                    int position = 1;
                    ps.setString(position++, user.userId().toString());
                    ps.setString(position++, club.clubId().toString());
                    ps.setString(position++, user.userName());
                    ps.setString(position++, user.friendlyName());
                    ps.setNull(position++, java.sql.Types.VARCHAR);
                    ps.setString(position++, user.notificationEmail());
                    ps.setString(position++, user.phoneNumber());
                    ps.setNull(position++, java.sql.Types.VARCHAR);
                    ps.setInt(position++, languageId);
                    ps.setTimestamp(position++, user.createdOn());
                    ps.setString(position++, user.createdByUserId().toString());
                    ps.setNull(position++, java.sql.Types.TIMESTAMP);
                    ps.setNull(position++, java.sql.Types.VARCHAR);
                    ps.setNull(position++, java.sql.Types.TIMESTAMP);
                    ps.setNull(position++, java.sql.Types.VARCHAR);
                    ps.setString(position, club.createdByUserId().toString());
                    ps.executeUpdate();
                    users.add(user);
                }
            }
        }
        return users;
    }

    private LegacyClub synthesizeClub(UUID countryId, Timestamp createdOn) {
        UUID clubId = newUuid();
        UUID createdBy = newUuid();
        String clubName = faker.aviation().airport() + " Flying Club";
        String clubKey = String.format("CLUB-%04d", faker.number().numberBetween(1000, 9999));
        return new LegacyClub(
                clubId,
                clubName,
                clubKey,
                faker.address().streetAddress(),
                faker.address().zipCode(),
                faker.address().city(),
                countryId,
                faker.phoneNumber().phoneNumber(),
                faker.phoneNumber().phoneNumber(),
                faker.internet().emailAddress(),
                faker.internet().url(),
                faker.name().fullName(),
                faker.internet().emailAddress(),
                faker.internet().emailAddress(),
                faker.internet().emailAddress(),
                faker.internet().emailAddress(),
                faker.internet().emailAddress(),
                faker.internet().emailAddress(),
                utcMicros(createdOn.toInstant()),
                createdBy);
    }

    private LegacyUser synthesizeUser(LegacyClub club, Timestamp createdOn) {
        UUID userId = newUuid();
        return new LegacyUser(
                userId,
                club.clubId(),
                faker.internet().username(),
                faker.name().fullName(),
                faker.internet().emailAddress(),
                faker.phoneNumber().cellPhone(),
                utcMicros(createdOn.toInstant()),
                club.createdByUserId());
    }

    private UUID newUuid() {
        long mostSignificantBits = faker.random().nextLong();
        long leastSignificantBits = faker.random().nextLong();
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private static Timestamp utcMicros(Instant source) {
        return Timestamp.from(source.truncatedTo(ChronoUnit.MICROS));
    }

    public record LegacyClub(
            UUID clubId,
            String clubname,
            String clubKey,
            @Nullable String address,
            @Nullable String zip,
            @Nullable String city,
            UUID countryId,
            @Nullable String phone,
            @Nullable String faxNumber,
            @Nullable String email,
            @Nullable String webPage,
            @Nullable String contact,
            @Nullable String sendAircraftStatisticReportTo,
            @Nullable String sendPlanningDayInfoMailTo,
            @Nullable String sendDeliveryMailExportTo,
            @Nullable String sendTrialFlightRegistrationOperatorEmail,
            @Nullable String sendPassengerFlightRegistrationOperatorEmail,
            @Nullable String replyToEmailAddress,
            Timestamp createdOn,
            UUID createdByUserId) {
    }

    public record LegacyUser(
            UUID userId,
            UUID clubId,
            String userName,
            String friendlyName,
            String notificationEmail,
            @Nullable String phoneNumber,
            Timestamp createdOn,
            UUID createdByUserId) {
    }

    public record SeededFixture(
            UUID switzerlandCountryId,
            int germanLanguageLegacyId,
            int activeClubStateLegacyId,
            List<LegacyClub> clubs,
            List<LegacyUser> users) {
    }
}
