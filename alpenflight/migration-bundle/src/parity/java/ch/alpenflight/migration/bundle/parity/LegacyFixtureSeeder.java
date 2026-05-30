package ch.alpenflight.migration.bundle.parity;

import java.sql.Connection;
import java.sql.PreparedStatement;
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

/**
 * Faker-only deterministic seeder for the legacy MSSQL fixture. Pins the RNG
 * to a sysprop-controlled seed ({@code parity.seed}; default 42) so a
 * re-ordering of Faker calls — even logically equivalent — is caught by the
 * downstream UUID drift. ≥ 2 Clubs mandatory (exercises per-bundle Person
 * sub-map / cross-tenant FK sweep); seed-time UTC offset 00:00 on every
 * timestamp removes the MSSQL {@code datetime2(7)} → Postgres
 * {@code timestamptz} TZ ambiguity (S-183 Edge cases).
 *
 * <p><strong>Vertical-slice scope (S-187).</strong> Seeds three legacy
 * tables — {@code Countries}, {@code Clubs}, {@code Users} — at minimal
 * shape sufficient for the three corresponding mappers. The full multi-
 * actor audit-fixture, sparse-enum coverage, FlightAirState=5 row, tow
 * chain, PersonCategory tree, degenerate AircraftReservation range, and
 * sparse-enum value-set coverage land at S-187a. Each obligation referenced
 * in the S-187 design notes is intentionally absent here; the harness still
 * proves the producer / envelope / consumer / diff axis with the three
 * seeded entities.
 */
public final class LegacyFixtureSeeder {

    /** Number of Clubs the seeder writes. Hard-pinned at 2 per AC. */
    public static final int CLUB_COUNT = 2;

    /** Users per Club. Vertical-slice value; S-187a scales via parity.scale. */
    public static final int USERS_PER_CLUB = 3;

    /**
     * V2 seed key the {@link ch.alpenflight.migration.bundle.identity.CountryMapper}
     * binds against — kept narrow so the round-trip is decidable without
     * a full ISO-3166 corpus. Both rows match V2's seed by {@code iso2_code}.
     */
    static final List<LegacyCountry> SEEDED_COUNTRIES = List.of(
            new LegacyCountry(UUID.fromString("019e2e15-2c00-73e8-8000-0000000003e8"), "AF"),
            new LegacyCountry(UUID.fromString("019e2e15-2c00-77e9-8000-0000000007e9"), "CH"));

    /** {@code club_state_id} seed bound to V2's {@code legacy_int_id = 1}. */
    static final int ACTIVE_CLUB_STATE_LEGACY_INT_ID = 1;

    /** {@code language_id} seed bound to V2's {@code legacy_int_id = 2000} (Deutsch). */
    static final int DEUTSCH_LANGUAGE_LEGACY_INT_ID = 2000;

    private final long seed;
    private final Faker faker;

    public LegacyFixtureSeeder(long seed) {
        this.seed = seed;
        this.faker = new Faker(new Random(seed));
    }

    public long seed() {
        return seed;
    }

    /**
     * Applies the seed to the legacy MSSQL connection. Caller manages the
     * transaction. Returns the synthesized rows so the diff engine can
     * assert producer/consumer parity per Club.
     */
    public SeededFixture seedInto(Connection legacyConnection) throws SQLException {
        legacyConnection.setAutoCommit(false);
        try {
            seedCountries(legacyConnection, SEEDED_COUNTRIES);
            List<LegacyClub> clubs = seedClubs(legacyConnection, SEEDED_COUNTRIES);
            List<LegacyUser> users = seedUsers(legacyConnection, clubs);
            legacyConnection.commit();
            return new SeededFixture(SEEDED_COUNTRIES, clubs, users);
        } catch (SQLException failure) {
            legacyConnection.rollback();
            throw failure;
        }
    }

    private static void seedCountries(Connection connection, List<LegacyCountry> countries)
            throws SQLException {
        String sql = "INSERT INTO Countries (CountryId, CountryCodeIso2) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (LegacyCountry country : countries) {
                ps.setString(1, country.countryId().toString());
                ps.setString(2, country.iso2Code());
                ps.executeUpdate();
            }
        }
    }

    private List<LegacyClub> seedClubs(Connection connection, List<LegacyCountry> countries)
            throws SQLException {
        Instant frozenInstant = Instant.parse("2024-01-01T00:00:00Z");
        Timestamp createdOn = Timestamp.from(frozenInstant);
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
                  DeletedOn, DeletedByUserId)
                VALUES (?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < CLUB_COUNT; i++) {
                LegacyClub club = synthesizeClub(countries.get(i % countries.size()), createdOn);
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
                ps.setInt(position++, ACTIVE_CLUB_STATE_LEGACY_INT_ID);
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
                ps.setNull(position, java.sql.Types.VARCHAR);
                ps.executeUpdate();
                clubs.add(club);
            }
        }
        return clubs;
    }

    private List<LegacyUser> seedUsers(Connection connection, List<LegacyClub> clubs)
            throws SQLException {
        Instant frozenInstant = Instant.parse("2024-01-02T00:00:00Z");
        Timestamp createdOn = Timestamp.from(frozenInstant);
        List<LegacyUser> users = new ArrayList<>(clubs.size() * USERS_PER_CLUB);
        String sql = """
                INSERT INTO Users (
                  UserId, ClubId, UserName, FriendlyName, PersonId,
                  NotificationEmail, PhoneNumber, Remarks, LanguageId,
                  CreatedOn, CreatedByUserId,
                  ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId)
                VALUES (?, ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?,
                        ?, ?, ?, ?)
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
                    ps.setInt(position++, DEUTSCH_LANGUAGE_LEGACY_INT_ID);
                    ps.setTimestamp(position++, user.createdOn());
                    ps.setString(position++, user.createdByUserId().toString());
                    ps.setNull(position++, java.sql.Types.TIMESTAMP);
                    ps.setNull(position++, java.sql.Types.VARCHAR);
                    ps.setNull(position++, java.sql.Types.TIMESTAMP);
                    ps.setNull(position, java.sql.Types.VARCHAR);
                    ps.executeUpdate();
                    users.add(user);
                }
            }
        }
        return users;
    }

    private LegacyClub synthesizeClub(LegacyCountry country, Timestamp createdOn) {
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
                country.countryId(),
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
                createdOn,
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
                truncateToMicros(createdOn),
                club.createdByUserId());
    }

    private UUID newUuid() {
        // Faker-driven random UUID — bound to the seed via the shared RNG
        // so the harness output is byte-for-byte deterministic.
        long mostSignificantBits = faker.random().nextLong();
        long leastSignificantBits = faker.random().nextLong();
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private static Timestamp truncateToMicros(Timestamp source) {
        // MSSQL datetime2(7) → Postgres timestamptz precision shift: pin to
        // microseconds on both sides so the diff engine sees byte-identical
        // values regardless of the JDBC driver's nanosecond handling.
        Instant truncated = source.toInstant().truncatedTo(ChronoUnit.MICROS);
        return Timestamp.from(truncated);
    }

    /** Synthetic legacy Country row. SYSTEM_GLOBAL — bridged by V2 seed UUIDs. */
    public record LegacyCountry(UUID countryId, String iso2Code) {
    }

    /** Synthetic legacy Club row mirroring the FLSTest Clubs columns the mapper reads. */
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

    /** Synthetic legacy User row mirroring the FLSTest Users columns the mapper reads. */
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

    /** Result of {@link #seedInto} — handed to the diff engine for per-Club row-count expectations. */
    public record SeededFixture(
            List<LegacyCountry> countries,
            List<LegacyClub> clubs,
            List<LegacyUser> users) {
    }
}
