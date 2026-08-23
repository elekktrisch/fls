package ch.alpenflight.tenancy.sandbox;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateChangeRequest;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.aircraft.domain.Immatriculation;
import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.persons.application.PersonDtos.LicenceFlags;
import ch.alpenflight.persons.application.PersonDtos.PersonClubRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonCreateRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonListItem;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.AircraftStateId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.tenancy.Tenants;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SandboxSeeder {

    public static final int HIGHEST_SUPPORTED_SEAT_NUMBER = 26;

    private static final String COUNTRY_ISO2_SWITZERLAND = "CH";

    private static final String LOCATION_TYPE_GLIDER_AIRFIELD = "GLIDER_AIRFIELD";
    private static final String LOCATION_TYPE_CONCRETE_RUNWAY = "CONCRETE_RUNWAY";
    private static final String LOCATION_TYPE_GRASS_RUNWAY = "GRASS_RUNWAY";

    private static final String AIRCRAFT_TYPE_GLIDER = "GLIDER";
    private static final String AIRCRAFT_TYPE_MOTOR_GLIDER = "MOTOR_GLIDER";
    private static final String AIRCRAFT_TYPE_MOTOR_AIRCRAFT = "MOTOR_AIRCRAFT";
    private static final String AIRCRAFT_STATE_AIRWORTHY = "OK";

    private static final String MEMBER_STATE_ACTIVE = "active";
    private static final String MEMBER_STATE_PASSIVE = "passive";

    private static final int AIRWORTHY_SINCE_DAYS_BEFORE_THE_RUN_DATE = 365;
    private static final int MEDICAL_VALID_MONTHS_AFTER_THE_RUN_DATE = 14;
    private static final int INSTRUCTOR_RATING_VALID_MONTHS_AFTER_THE_RUN_DATE = 20;

    private static final LocalDate OLDEST_MEMBER_BIRTHDAY = LocalDate.of(1969, 4, 12);
    private static final int BIRTH_YEARS_BETWEEN_TWO_MEMBERS = 5;

    private enum SandboxMemberRole {
        GLIDER_PILOT,
        GLIDER_INSTRUCTOR,
        GLIDER_TRAINEE,
        TOW_PILOT,
        MOTOR_PILOT,
        MOTOR_INSTRUCTOR,
        WINCH_OPERATOR
    }

    private record SandboxLocationSpec(String name,
                                       String shortName,
                                       String icaoCode,
                                       String locationTypeCode,
                                       String latitude,
                                       String longitude) {}

    private record SandboxAircraftSpec(String immatriculation,
                                       String typeCode,
                                       String manufacturerName,
                                       String model,
                                       @Nullable String competitionSign,
                                       int nrOfSeats,
                                       boolean towPlane) {}

    private record SandboxPersonSpec(String firstname,
                                     String lastname,
                                     String street,
                                     String zip,
                                     String city,
                                     String memberStateName,
                                     Set<SandboxMemberRole> roles) {}

    private static final List<SandboxLocationSpec> LOCATION_SPECS = List.of(
            new SandboxLocationSpec("Schänis", "SCHA", "LSZX",
                    LOCATION_TYPE_GLIDER_AIRFIELD, "47.1717", "9.0392"),
            new SandboxLocationSpec("Bern-Belp", "BERN", "LSZB",
                    LOCATION_TYPE_CONCRETE_RUNWAY, "46.9141", "7.4971"),
            new SandboxLocationSpec("Saanen", "SAAN", "LSGK",
                    LOCATION_TYPE_CONCRETE_RUNWAY, "46.4875", "7.2508"),
            new SandboxLocationSpec("Amlikon", "AMLI", "LSPD",
                    LOCATION_TYPE_GRASS_RUNWAY, "47.5906", "9.0483"));

    private static final List<SandboxPersonSpec> ROSTER_SPECS = List.of(
            new SandboxPersonSpec("Anna", "Meier", "Seestrasse 14", "8001", "Zürich",
                    MEMBER_STATE_ACTIVE,
                    Set.of(SandboxMemberRole.GLIDER_PILOT, SandboxMemberRole.GLIDER_INSTRUCTOR)),
            new SandboxPersonSpec("Thomas", "Brunner", "Talackerstrasse 9", "8400", "Winterthur",
                    MEMBER_STATE_ACTIVE,
                    Set.of(SandboxMemberRole.TOW_PILOT, SandboxMemberRole.MOTOR_PILOT)),
            new SandboxPersonSpec("Nicolas", "Rochat", "Bahnhofstrasse 27", "8610", "Uster",
                    MEMBER_STATE_ACTIVE,
                    Set.of(SandboxMemberRole.GLIDER_PILOT)),
            new SandboxPersonSpec("Sandra", "Keller", "Rütiwiesstrasse 3", "8620", "Wetzikon",
                    MEMBER_STATE_ACTIVE,
                    Set.of(SandboxMemberRole.GLIDER_TRAINEE)),
            new SandboxPersonSpec("Marc", "Steiner", "Hafenweg 5", "8640", "Rapperswil",
                    MEMBER_STATE_ACTIVE,
                    Set.of(SandboxMemberRole.GLIDER_PILOT, SandboxMemberRole.WINCH_OPERATOR)),
            new SandboxPersonSpec("Livia", "Bianchi", "Mythenquai 40", "8003", "Zürich",
                    MEMBER_STATE_PASSIVE,
                    Set.of(SandboxMemberRole.MOTOR_PILOT, SandboxMemberRole.MOTOR_INSTRUCTOR)));

    private final SandboxReferenceData referenceData;
    private final LocationsService locations;
    private final AircraftsService aircraftWrites;
    private final AircraftRepository aircraftReads;
    private final PersonsService persons;
    private final SandboxOperationsSeeder operations;
    private final Clock clock;
    private final TransactionTemplate oneTransactionPerSeatSeed;

    public SandboxSeeder(SandboxReferenceData referenceData,
                         LocationsService locations,
                         AircraftsService aircraftWrites,
                         AircraftRepository aircraftReads,
                         PersonsService persons,
                         SandboxOperationsSeeder operations,
                         Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.referenceData = referenceData;
        this.locations = locations;
        this.aircraftWrites = aircraftWrites;
        this.aircraftReads = aircraftReads;
        this.persons = persons;
        this.operations = operations;
        this.clock = clock;
        this.oneTransactionPerSeatSeed = new TransactionTemplate(transactionManager);
    }

    public SandboxMasterdata seed(UUID seatClubId, int seatNumber) {
        if (seatClubId == null) {
            throw new IllegalArgumentException("seatClubId must not be null");
        }
        if (seatNumber < 1 || seatNumber > HIGHEST_SUPPORTED_SEAT_NUMBER) {
            throw new IllegalArgumentException(
                    "seatNumber must be between 1 and " + HIGHEST_SUPPORTED_SEAT_NUMBER
                            + " so every seat gets its own globally unique immatriculation block, was "
                            + seatNumber);
        }
        Supplier<SandboxMasterdata> seedInsideTheSeatTenant =
                () -> inOneTransaction(seatClubId, seatNumber);
        return Tenants.runAs(seatClubId, seedInsideTheSeatTenant);
    }

    private SandboxMasterdata inOneTransaction(UUID seatClubId, int seatNumber) {
        SandboxMasterdata seeded = oneTransactionPerSeatSeed.execute(status -> {
            referenceData.seedTheDefaultsEveryClubNeeds(seatClubId);
            List<LocationId> airfields = seedAirfields();
            LocationId home = airfields.get(0);
            SandboxMasterdata.SandboxFleet fleet = seedFleet(seatNumber, home);
            SandboxMasterdata.SandboxRoster roster = seedRoster(seatNumber);
            return new SandboxMasterdata(
                    seatClubId,
                    home,
                    List.copyOf(airfields.subList(1, airfields.size())),
                    fleet,
                    roster,
                    operations.seed(fleet, roster, home));
        });
        if (seeded == null) {
            throw new IllegalStateException("the sandbox seed transaction returned no result");
        }
        return seeded;
    }

    private List<LocationId> seedAirfields() {
        Map<String, LocationId> alreadySeededByIcao = new HashMap<>();
        for (LocationListItem existing : locations.listLocations()) {
            String icao = existing.icaoCode();
            if (icao != null) {
                alreadySeededByIcao.put(icao.toUpperCase(Locale.ROOT), existing.id());
            }
        }
        CountryId switzerland = referenceData.countryOfIso2Code(COUNTRY_ISO2_SWITZERLAND);
        List<LocationId> airfields = new ArrayList<>();
        for (SandboxLocationSpec spec : LOCATION_SPECS) {
            LocationId seeded = alreadySeededByIcao.get(spec.icaoCode());
            if (seeded == null) {
                seeded = locations.createLocation(new LocationCreateRequest(
                        spec.name(), spec.shortName(), switzerland,
                        referenceData.locationTypeOfCode(spec.locationTypeCode()), spec.icaoCode(),
                        spec.latitude(), spec.longitude(),
                        null, null, null, null, null, null, null, null,
                        false, false, false, null)).id();
            }
            airfields.add(seeded);
        }
        return airfields;
    }

    private SandboxMasterdata.SandboxFleet seedFleet(int seatNumber, LocationId homebase) {
        AircraftStateId airworthy = referenceData.aircraftStateOfCode(AIRCRAFT_STATE_AIRWORTHY);
        Instant airworthySince = Instant.now(clock)
                .minus(AIRWORTHY_SINCE_DAYS_BEFORE_THE_RUN_DATE, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        List<AircraftId> fleet = new ArrayList<>();
        for (SandboxAircraftSpec spec : fleetSpecsFor(seatNumber)) {
            AircraftId seeded = alreadyRegistered(spec.immatriculation());
            if (seeded == null) {
                boolean enginelessGliderNeedsALaunch =
                        AIRCRAFT_TYPE_GLIDER.equals(spec.typeCode());
                seeded = aircraftWrites.registerAircraft(new AircraftCreateRequest(
                        referenceData.aircraftTypeOfCode(spec.typeCode()), spec.immatriculation(),
                        spec.manufacturerName(), spec.model(), spec.competitionSign(),
                        null, null, null, null, null, null, spec.nrOfSeats(),
                        null, null, homebase, null,
                        enginelessGliderNeedsALaunch,
                        enginelessGliderNeedsALaunch,
                        enginelessGliderNeedsALaunch,
                        spec.towPlane(), null, null)).id();
                aircraftWrites.changeAircraftState(seeded,
                        new AircraftStateChangeRequest(airworthy, airworthySince, null, null));
            }
            fleet.add(seeded);
        }
        return new SandboxMasterdata.SandboxFleet(
                fleet.get(0), fleet.get(1), fleet.get(2), fleet.get(3));
    }

    private @Nullable AircraftId alreadyRegistered(String immatriculation) {
        String normalized = Immatriculation.of(immatriculation).storedUppercaseKeepingHyphens();
        return aircraftReads.findActiveByImmatriculation(normalized)
                .map(Aircraft::getId)
                .orElse(null);
    }

    private SandboxMasterdata.SandboxRoster seedRoster(int seatNumber) {
        Map<String, PersonId> alreadySeededByFullName = new HashMap<>();
        for (PersonListItem existing : persons.listInCurrentTenant()) {
            alreadySeededByFullName.put(
                    fullName(existing.firstname(), existing.lastname()), existing.id());
        }
        UUID activeMemberState = referenceData.memberStateOfName(MEMBER_STATE_ACTIVE);
        UUID passiveMemberState = referenceData.memberStateOfName(MEMBER_STATE_PASSIVE);
        List<PersonId> roster = new ArrayList<>();
        for (int i = 0; i < ROSTER_SPECS.size(); i++) {
            SandboxPersonSpec spec = ROSTER_SPECS.get(i);
            PersonId seeded = alreadySeededByFullName.get(
                    fullName(spec.firstname(), spec.lastname()));
            if (seeded == null) {
                UUID memberState = MEMBER_STATE_PASSIVE.equals(spec.memberStateName())
                        ? passiveMemberState
                        : activeMemberState;
                seeded = persons.createPerson(
                        createRequestFor(spec, i, seatNumber, memberState)).id();
            }
            roster.add(seeded);
        }
        return new SandboxMasterdata.SandboxRoster(
                roster.get(0), roster.get(1), roster.get(2),
                roster.get(3), roster.get(4), roster.get(5));
    }

    private PersonCreateRequest createRequestFor(SandboxPersonSpec spec,
                                                 int rosterIndex,
                                                 int seatNumber,
                                                 UUID memberStateId) {
        String mailbox = fullName(spec.firstname(), spec.lastname()).replace(' ', '.');
        return new PersonCreateRequest(
                spec.firstname(), spec.lastname(), null, null,
                spec.street(), null, spec.zip(), spec.city(), null,
                referenceData.countryOfIso2Code(COUNTRY_ISO2_SWITZERLAND).value(),
                null,
                "+41 79 %03d %02d %02d".formatted(400 + seatNumber, 10 + rosterIndex, rosterIndex),
                null, null,
                "%s@demo%d.example.com".formatted(mailbox, seatNumber),
                null, false,
                birthdayOf(rosterIndex, seatNumber),
                licencesFor(spec.roles(), rosterIndex),
                membershipFor(spec, rosterIndex, memberStateId),
                null, false, true);
    }

    private static LocalDate birthdayOf(int rosterIndex, int seatNumber) {
        return OLDEST_MEMBER_BIRTHDAY
                .plusYears((long) rosterIndex * BIRTH_YEARS_BETWEEN_TWO_MEMBERS)
                .plusDays(seatNumber);
    }

    private static PersonClubRequest membershipFor(SandboxPersonSpec spec,
                                                   int rosterIndex,
                                                   UUID memberStateId) {
        Set<SandboxMemberRole> roles = spec.roles();
        return new PersonClubRequest(
                "M-%03d".formatted(rosterIndex + 1),
                memberStateId,
                roles.contains(SandboxMemberRole.MOTOR_PILOT)
                        || roles.contains(SandboxMemberRole.TOW_PILOT)
                        || roles.contains(SandboxMemberRole.MOTOR_INSTRUCTOR),
                roles.contains(SandboxMemberRole.TOW_PILOT),
                roles.contains(SandboxMemberRole.GLIDER_INSTRUCTOR),
                roles.contains(SandboxMemberRole.GLIDER_PILOT)
                        || roles.contains(SandboxMemberRole.GLIDER_INSTRUCTOR),
                roles.contains(SandboxMemberRole.GLIDER_TRAINEE),
                false,
                roles.contains(SandboxMemberRole.WINCH_OPERATOR),
                roles.contains(SandboxMemberRole.MOTOR_INSTRUCTOR),
                true, true, true,
                !MEMBER_STATE_PASSIVE.equals(spec.memberStateName()));
    }

    private LicenceFlags licencesFor(Set<SandboxMemberRole> roles, int rosterIndex) {
        LocalDate runDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        boolean gliderInstructor = roles.contains(SandboxMemberRole.GLIDER_INSTRUCTOR);
        boolean motorInstructor = roles.contains(SandboxMemberRole.MOTOR_INSTRUCTOR);
        boolean motorPilot = roles.contains(SandboxMemberRole.MOTOR_PILOT)
                || roles.contains(SandboxMemberRole.TOW_PILOT)
                || motorInstructor;
        boolean gliderPilot = roles.contains(SandboxMemberRole.GLIDER_PILOT) || gliderInstructor;
        LocalDate medicalExpiry = runDate.plusMonths(
                MEDICAL_VALID_MONTHS_AFTER_THE_RUN_DATE + (long) rosterIndex);
        LocalDate ratingExpiry = runDate.plusMonths(
                INSTRUCTOR_RATING_VALID_MONTHS_AFTER_THE_RUN_DATE + (long) rosterIndex);
        return new LicenceFlags(
                motorPilot,
                roles.contains(SandboxMemberRole.TOW_PILOT),
                gliderInstructor,
                gliderPilot,
                roles.contains(SandboxMemberRole.GLIDER_TRAINEE),
                false,
                motorPilot,
                roles.contains(SandboxMemberRole.WINCH_OPERATOR),
                motorInstructor,
                false,
                "CH-%04d".formatted(1000 + rosterIndex),
                null,
                medicalExpiry,
                null,
                gliderInstructor ? ratingExpiry : null,
                motorInstructor ? ratingExpiry : null,
                null,
                gliderPilot,
                motorPilot,
                gliderPilot);
    }

    private static List<SandboxAircraftSpec> fleetSpecsFor(int seatNumber) {
        return List.of(
                new SandboxAircraftSpec("HB-3" + (100 + seatNumber), AIRCRAFT_TYPE_GLIDER,
                        "Schleicher", "ASK 21", "SD" + seatNumber, 2, false),
                new SandboxAircraftSpec("HB-3" + (200 + seatNumber), AIRCRAFT_TYPE_GLIDER,
                        "Schempp-Hirth", "Discus CS", "DC" + seatNumber, 1, false),
                new SandboxAircraftSpec("HB-2" + (300 + seatNumber), AIRCRAFT_TYPE_MOTOR_GLIDER,
                        "Diamond", "HK 36 TTC Super Dimona", null, 2, false),
                new SandboxAircraftSpec("HB-KD" + (char) ('A' + seatNumber - 1),
                        AIRCRAFT_TYPE_MOTOR_AIRCRAFT, "Robin", "DR 400/180", null, 4, true));
    }

    private static String fullName(String firstname, String lastname) {
        return (firstname + " " + lastname).toLowerCase(Locale.ROOT);
    }
}
