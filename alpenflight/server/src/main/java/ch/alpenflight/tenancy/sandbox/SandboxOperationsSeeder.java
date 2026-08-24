package ch.alpenflight.tenancy.sandbox;

import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightCrewItem;
import ch.alpenflight.flights.application.FlightDtos.FlightListItem;
import ch.alpenflight.flights.application.FlightsService;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeListItem;
import ch.alpenflight.flighttypes.application.FlightTypesService;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayCreateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayDetail;
import ch.alpenflight.planning.application.PlanningDaysService;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationCreateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationListItem;
import ch.alpenflight.reservations.application.AircraftReservationsService;
import ch.alpenflight.tenancy.sandbox.SandboxMasterdata.SandboxFleet;
import ch.alpenflight.tenancy.sandbox.SandboxMasterdata.SandboxOperations;
import ch.alpenflight.tenancy.sandbox.SandboxMasterdata.SandboxRoster;
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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class SandboxOperationsSeeder {

    private static final int FLIGHT_HISTORY_WINDOW_DAYS = 30;
    private static final int PLANNING_DAY_DAYS_AFTER_THE_RUN_DATE = 3;
    private static final int WIDEST_FLIGHT_READ_BACK_THE_LIST_SERVICE_ALLOWS = 200;

    private static final UUID START_TYPE_WINCH_LAUNCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final UUID START_TYPE_MOTOR =
            UUID.fromString("019e2e15-2c00-7fa2-8000-000000000fa2");

    private static final String FLIGHT_TYPE_TRAINING = "training";
    private static final String FLIGHT_TYPE_GLIDER_TOW = "glider-tow";
    private static final String FLIGHT_TYPE_PRIVATE = "private";

    private static final String PLANNING_DAY_INFO = "Flugbetrieb Segelflug — Start 09:00";

    private static final List<Integer> FLYING_DAYS_BEFORE_THE_RUN_DATE =
            List.of(2, 5, 9, 12, 19, 26);

    private enum FleetSlot {
        CLUB_GLIDER,
        SINGLE_SEAT_GLIDER,
        TOURING_MOTOR_GLIDER,
        TOW_PLANE
    }

    private enum RosterSlot {
        GLIDER_INSTRUCTOR,
        TOW_PILOT,
        GLIDER_PILOT,
        GLIDER_TRAINEE,
        MOTOR_INSTRUCTOR
    }

    private record SandboxFlightSpec(FlightAircraftType flightAircraftType,
                                     FleetSlot aircraft,
                                     UUID startTypeId,
                                     String flightTypeName,
                                     RosterSlot pilotInCommand,
                                     @Nullable RosterSlot instructor,
                                     int takeOffHourUtc,
                                     int durationMinutes,
                                     String comment) {

        boolean soloFlight() {
            return instructor == null && flightAircraftType == FlightAircraftType.GLIDER;
        }
    }

    private record SandboxReservationSpec(int daysAfterTheRunDate,
                                          FleetSlot aircraft,
                                          RosterSlot pilot,
                                          String flightTypeName,
                                          int startHourUtc,
                                          int durationMinutes,
                                          String remarks) {}

    private static final List<SandboxFlightSpec> ONE_FLYING_DAY = List.of(
            new SandboxFlightSpec(FlightAircraftType.TOW, FleetSlot.TOW_PLANE,
                    START_TYPE_AEROTOW, FLIGHT_TYPE_GLIDER_TOW,
                    RosterSlot.TOW_PILOT, null, 10, 15, "Schlepp auf 600 m"),
            new SandboxFlightSpec(FlightAircraftType.GLIDER, FleetSlot.CLUB_GLIDER,
                    START_TYPE_AEROTOW, FLIGHT_TYPE_TRAINING,
                    RosterSlot.GLIDER_TRAINEE, RosterSlot.GLIDER_INSTRUCTOR,
                    10, 45, "Schulung Platzrunden"),
            new SandboxFlightSpec(FlightAircraftType.GLIDER, FleetSlot.SINGLE_SEAT_GLIDER,
                    START_TYPE_WINCH_LAUNCH, FLIGHT_TYPE_PRIVATE,
                    RosterSlot.GLIDER_PILOT, null, 12, 180, "Streckenflug Alpenrand"),
            new SandboxFlightSpec(FlightAircraftType.MOTOR, FleetSlot.TOURING_MOTOR_GLIDER,
                    START_TYPE_MOTOR, FLIGHT_TYPE_TRAINING,
                    RosterSlot.MOTOR_INSTRUCTOR, null, 14, 90, "Übungsflug Motorsegler"));

    private static final List<SandboxReservationSpec> UPCOMING_RESERVATIONS = List.of(
            new SandboxReservationSpec(1, FleetSlot.CLUB_GLIDER, RosterSlot.GLIDER_INSTRUCTOR,
                    FLIGHT_TYPE_TRAINING, 9, 180, "Schulung mit Fluglehrer"),
            new SandboxReservationSpec(2, FleetSlot.SINGLE_SEAT_GLIDER, RosterSlot.GLIDER_PILOT,
                    FLIGHT_TYPE_PRIVATE, 10, 360, "Streckenflug geplant"),
            new SandboxReservationSpec(4, FleetSlot.TOURING_MOTOR_GLIDER,
                    RosterSlot.MOTOR_INSTRUCTOR, FLIGHT_TYPE_TRAINING, 8, 180,
                    "Übungsflug Navigation"),
            new SandboxReservationSpec(6, FleetSlot.TOW_PLANE, RosterSlot.TOW_PILOT,
                    FLIGHT_TYPE_GLIDER_TOW, 13, 120, "Schleppdienst Nachmittag"),
            new SandboxReservationSpec(9, FleetSlot.CLUB_GLIDER, RosterSlot.GLIDER_PILOT,
                    FLIGHT_TYPE_PRIVATE, 9, 240, "Doppelsitzer reserviert"),
            new SandboxReservationSpec(12, FleetSlot.TOURING_MOTOR_GLIDER,
                    RosterSlot.MOTOR_INSTRUCTOR, FLIGHT_TYPE_PRIVATE, 11, 300,
                    "Überlandflug nach Sion"));

    private final FlightsService flights;
    private final AircraftReservationsService reservations;
    private final PlanningDaysService planningDays;
    private final FlightTypesService flightTypes;
    private final Clock clock;

    SandboxOperationsSeeder(FlightsService flights,
                            AircraftReservationsService reservations,
                            PlanningDaysService planningDays,
                            FlightTypesService flightTypes,
                            Clock clock) {
        this.flights = flights;
        this.reservations = reservations;
        this.planningDays = planningDays;
        this.flightTypes = flightTypes;
        this.clock = clock;
    }

    boolean holdsTheSeedOfTheRunDate() {
        LocalDate planningDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .plusDays(PLANNING_DAY_DAYS_AFTER_THE_RUN_DATE);
        return planningDays.overviewFuture().stream()
                .anyMatch(existing -> planningDate.equals(existing.planningDate()));
    }

    SandboxOperations seed(SandboxFleet fleet, SandboxRoster roster, LocationId homeAirfield) {
        LocalDate runDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Map<String, FlightTypeId> flightTypesByName = flightTypesByName();
        return new SandboxOperations(
                seedTheFlightHistory(runDate, fleet, roster, homeAirfield, flightTypesByName),
                seedTheUpcomingReservations(
                        runDate, fleet, roster, homeAirfield, flightTypesByName),
                seedTheNextPlanningDay(runDate, homeAirfield));
    }

    private List<FlightId> seedTheFlightHistory(LocalDate runDate,
                                                SandboxFleet fleet,
                                                SandboxRoster roster,
                                                LocationId homeAirfield,
                                                Map<String, FlightTypeId> flightTypesByName) {
        Map<String, FlightId> alreadySeeded = flightsAlreadySeededByFlyingDayAndAircraft(runDate);
        List<FlightId> history = new ArrayList<>();
        for (int daysAgo : FLYING_DAYS_BEFORE_THE_RUN_DATE) {
            LocalDate flightDate = runDate.minusDays(daysAgo);
            for (SandboxFlightSpec spec : ONE_FLYING_DAY) {
                AircraftId aircraft = aircraftOf(fleet, spec.aircraft());
                FlightId seeded = alreadySeeded.get(
                        flyingDayAndAircraftKey(flightDate, aircraft));
                if (seeded == null) {
                    seeded = flights.createFlight(flightCreateRequestFor(
                            spec, flightDate, aircraft, roster,
                            homeAirfield, flightTypesByName)).id();
                }
                history.add(seeded);
            }
        }
        return List.copyOf(history);
    }

    private Map<String, FlightId> flightsAlreadySeededByFlyingDayAndAircraft(LocalDate runDate) {
        Map<String, FlightId> byFlyingDayAndAircraft = new HashMap<>();
        List<FlightListItem> historyWindow = flights.listFlights(
                runDate.minusDays(FLIGHT_HISTORY_WINDOW_DAYS), runDate,
                null, WIDEST_FLIGHT_READ_BACK_THE_LIST_SERVICE_ALLOWS, null).items();
        for (FlightListItem existing : historyWindow) {
            LocalDate flightDate = existing.flightDate();
            if (flightDate != null) {
                byFlyingDayAndAircraft.putIfAbsent(
                        flyingDayAndAircraftKey(flightDate, existing.aircraftId()), existing.id());
            }
        }
        return byFlyingDayAndAircraft;
    }

    private static String flyingDayAndAircraftKey(LocalDate flightDate, AircraftId aircraft) {
        return flightDate + "|" + aircraft.value();
    }

    private FlightCreateRequest flightCreateRequestFor(SandboxFlightSpec spec,
                                                       LocalDate flightDate,
                                                       AircraftId aircraft,
                                                       SandboxRoster roster,
                                                       LocationId homeAirfield,
                                                       Map<String, FlightTypeId> flightTypesByName) {
        Instant takeOff = flightDate.atTime(spec.takeOffHourUtc(), 0).toInstant(ZoneOffset.UTC);
        Instant landing = takeOff.plus(spec.durationMinutes(), ChronoUnit.MINUTES);
        return new FlightCreateRequest(
                spec.flightAircraftType(), aircraft, flightDate,
                takeOff, landing, takeOff, landing,
                homeAirfield, homeAirfield,
                null, null, null, null,
                flightTypeOf(flightTypesByName, spec.flightTypeName()),
                spec.startTypeId(),
                (short) 1, (short) 1,
                false, false,
                null, null,
                spec.comment(), null, null, null, null, null,
                spec.soloFlight(),
                crewFor(spec, roster, takeOff, landing));
    }

    private static List<FlightCrewItem> crewFor(SandboxFlightSpec spec,
                                                SandboxRoster roster,
                                                Instant takeOff,
                                                Instant landing) {
        List<FlightCrewItem> crew = new ArrayList<>();
        crew.add(new FlightCrewItem(
                personOf(roster, spec.pilotInCommand()), FlightCrewTypeIds.PILOT_OR_STUDENT,
                takeOff, landing, null, null, (short) 1, (short) 1));
        RosterSlot instructor = spec.instructor();
        if (instructor != null) {
            crew.add(new FlightCrewItem(
                    personOf(roster, instructor), FlightCrewTypeIds.FLIGHT_INSTRUCTOR,
                    takeOff, landing, takeOff, landing, (short) 1, (short) 1));
        }
        return List.copyOf(crew);
    }

    private List<UUID> seedTheUpcomingReservations(LocalDate runDate,
                                                   SandboxFleet fleet,
                                                   SandboxRoster roster,
                                                   LocationId homeAirfield,
                                                   Map<String, FlightTypeId> flightTypesByName) {
        Map<String, UUID> alreadySeeded = reservationsAlreadySeededByDayAndAircraft();
        List<UUID> booked = new ArrayList<>();
        for (SandboxReservationSpec spec : UPCOMING_RESERVATIONS) {
            LocalDate day = runDate.plusDays(spec.daysAfterTheRunDate());
            AircraftId aircraft = aircraftOf(fleet, spec.aircraft());
            UUID seeded = alreadySeeded.get(reservationDayAndAircraftKey(day, aircraft));
            if (seeded == null) {
                Instant start = day.atTime(spec.startHourUtc(), 0).toInstant(ZoneOffset.UTC);
                seeded = reservations.createReservation(new AircraftReservationCreateRequest(
                        aircraft, personOf(roster, spec.pilot()), homeAirfield,
                        null, null,
                        flightTypeOf(flightTypesByName, spec.flightTypeName()).value(),
                        start, start.plus(spec.durationMinutes(), ChronoUnit.MINUTES),
                        false, spec.remarks())).id();
            }
            booked.add(seeded);
        }
        return List.copyOf(booked);
    }

    private Map<String, UUID> reservationsAlreadySeededByDayAndAircraft() {
        Map<String, UUID> byDayAndAircraft = new HashMap<>();
        for (AircraftReservationListItem existing : reservations.listFuture()) {
            LocalDate day = LocalDate.ofInstant(existing.start(), ZoneOffset.UTC);
            byDayAndAircraft.putIfAbsent(
                    reservationDayAndAircraftKey(day, existing.aircraftId()), existing.id());
        }
        return byDayAndAircraft;
    }

    private static String reservationDayAndAircraftKey(LocalDate day, AircraftId aircraft) {
        return day + "|" + aircraft.value();
    }

    private UUID seedTheNextPlanningDay(LocalDate runDate, LocationId homeAirfield) {
        LocalDate planningDate = runDate.plusDays(PLANNING_DAY_DAYS_AFTER_THE_RUN_DATE);
        for (PlanningDayDetail existing : planningDays.overviewFuture()) {
            if (planningDate.equals(existing.planningDate())
                    && homeAirfield.equals(existing.locationId())) {
                return existing.id();
            }
        }
        return planningDays.createPlanningDay(new PlanningDayCreateRequest(
                planningDate, homeAirfield, null, null, null, PLANNING_DAY_INFO)).id();
    }

    private Map<String, FlightTypeId> flightTypesByName() {
        Map<String, FlightTypeId> byName = new HashMap<>();
        for (FlightTypeListItem type : flightTypes.listFlightTypes()) {
            byName.putIfAbsent(type.flightTypeName().toLowerCase(Locale.ROOT), type.id());
        }
        return byName;
    }

    private static FlightTypeId flightTypeOf(Map<String, FlightTypeId> byName, String name) {
        FlightTypeId id = byName.get(name);
        if (id == null) {
            throw new IllegalStateException(
                    "the sandbox seed needs the flight type '" + name + "', which is absent");
        }
        return id;
    }

    private static AircraftId aircraftOf(SandboxFleet fleet, FleetSlot slot) {
        return switch (slot) {
            case CLUB_GLIDER -> fleet.clubGlider();
            case SINGLE_SEAT_GLIDER -> fleet.singleSeatGlider();
            case TOURING_MOTOR_GLIDER -> fleet.touringMotorGlider();
            case TOW_PLANE -> fleet.towPlane();
        };
    }

    private static PersonId personOf(SandboxRoster roster, RosterSlot slot) {
        return switch (slot) {
            case GLIDER_INSTRUCTOR -> roster.gliderInstructor();
            case TOW_PILOT -> roster.towPilot();
            case GLIDER_PILOT -> roster.gliderPilot();
            case GLIDER_TRAINEE -> roster.gliderTrainee();
            case MOTOR_INSTRUCTOR -> roster.motorInstructor();
        };
    }
}
