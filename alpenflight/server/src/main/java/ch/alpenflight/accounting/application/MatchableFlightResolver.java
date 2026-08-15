package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.MatchableFlight;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrew;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.referencedata.domain.FlightCrewType;
import ch.alpenflight.referencedata.domain.FlightCrewTypeRepository;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceType;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class MatchableFlightResolver {

    private final AircraftRepository aircraft;
    private final FlightTypeRepository flightTypes;
    private final LocationRepository locations;
    private final PersonRepository persons;
    private final FlightCrewTypeRepository flightCrewTypes;
    private final FlightCostBalanceTypeRepository costBalanceTypes;

    MatchableFlightResolver(AircraftRepository aircraft,
                            FlightTypeRepository flightTypes,
                            LocationRepository locations,
                            PersonRepository persons,
                            FlightCrewTypeRepository flightCrewTypes,
                            FlightCostBalanceTypeRepository costBalanceTypes) {
        this.aircraft = aircraft;
        this.flightTypes = flightTypes;
        this.locations = locations;
        this.persons = persons;
        this.flightCrewTypes = flightCrewTypes;
        this.costBalanceTypes = costBalanceTypes;
    }

    MatchableFlight resolve(Flight flight) {
        Map<UUID, Short> crewTypeLegacyById = crewTypeLegacyById();

        Optional<Aircraft> ac = aircraft.findActiveById(flight.getAircraftId());
        String immatriculation = ac.map(Aircraft::getImmatriculation).orElse(null);
        String homebaseIcao = ac.flatMap(a -> resolveLocationIcao(a.getHomebaseId())).orElse(null);

        Optional<FlightType> ft = resolveFlightType(flight.getFlightTypeId());
        String flightTypeCode = ft.map(FlightType::getFlightCode).orElse(null);
        String flightTypeName = ft.map(FlightType::getFlightTypeName).orElse(null);

        List<MatchableCrew> crew = resolveCrew(flight, crewTypeLegacyById);

        return MatchableFlight.builder(flight.getFlightAircraftType())
                .immatriculation(immatriculation)
                .flightTypeCode(flightTypeCode)
                .flightTypeName(flightTypeName)
                .startTypeId(asString(flight.getStartTypeId()))
                .startLocationIcao(resolveLocationIcao(flight.getStartLocationId()).orElse(null))
                .ldgLocationIcao(resolveLocationIcao(flight.getLdgLocationId()).orElse(null))
                .aircraftHomebase(homebaseIcao)
                .aircraftPresent(ac.isPresent())
                .towFlightTypeCode(null)
                .towedFlightTypeCodes(List.of())
                .crew(crew)
                .flightCostBalanceTypeId(resolveCostBalanceLegacyId(flight.getFlightCostBalanceTypeId()))
                .flightCostInvoiceRecipient(crewRecipient(
                        flight, FlightCrewTypeIds.FLIGHT_COST_INVOICE_RECIPIENT))
                .pilot(crewRecipient(flight, FlightCrewTypeIds.PILOT_OR_STUDENT))
                .flightDurationSeconds(flightDurationZeroBasedSeconds(flight))
                .nrOfLdgs(asInteger(flight.getNrOfLdgs()))
                .nrOfLdgsOnStartLocation(asInteger(flight.getNrOfLdgsOnStartLocation()))
                .noStartTimeInformation(flight.isNoStartTimeInformation())
                .noLdgTimeInformation(flight.isNoLdgTimeInformation())
                .instructorDisplayName(resolveInstructorDisplayName(flight))
                .build();
    }

    int flightDurationZeroBasedSeconds(Flight flight) {
        var start = flight.getStartDateTime();
        var ldg = flight.getLdgDateTime();
        if (start == null || ldg == null) {
            return 0;
        }
        double seconds = (ldg.toEpochMilli() - start.toEpochMilli()) / 1000.0;
        return (int) Math.round(seconds);
    }

    int engineRunningTimeSeconds(Flight flight) {
        long end = orZero(flight.getEngineEndOperatingCounterInSeconds());
        long start = orZero(flight.getEngineStartOperatingCounterInSeconds());
        long delta = end - start;
        return delta < 0 ? 0 : (int) delta;
    }

    private List<MatchableCrew> resolveCrew(Flight flight, Map<UUID, Short> crewTypeLegacyById) {
        List<MatchableCrew> resolved = new ArrayList<>();
        for (FlightCrew row : flight.getCrew()) {
            if (row.isDeleted()) {
                continue;
            }
            Short legacyCrewType = crewTypeLegacyById.get(row.getFlightCrewTypeId());
            String memberNumber = null;
            String memberStateId = null;
            PersonClub membership = membershipForDeliveryClub(row.getPersonId());
            if (membership != null) {
                memberNumber = membership.getMemberNumber();
                memberStateId = asString(membership.getMemberStateId());
            }
            resolved.add(MatchableCrew.of(
                    String.valueOf(legacyCrewType == null ? 0 : legacyCrewType),
                    memberNumber,
                    memberStateId,
                    List.of()));
        }
        return resolved;
    }

    private @Nullable PersonClub membershipForDeliveryClub(UUID personId) {
        return persons.findActiveById(personId)
                .flatMap(p -> p.getActivePersonClubs().stream().findFirst())
                .orElse(null);
    }

    private @Nullable String resolveInstructorDisplayName(Flight flight) {
        for (FlightCrew row : flight.getCrew()) {
            if (row.isDeleted() || !FlightCrewTypeIds.FLIGHT_INSTRUCTOR.equals(row.getFlightCrewTypeId())) {
                continue;
            }
            return persons.findActiveById(row.getPersonId())
                    .map(p -> (p.getFirstname() + " " + p.getLastname()).strip())
                    .orElse(null);
        }
        return null;
    }

    @Nullable Recipient crewRecipient(Flight flight, UUID crewTypeId) {
        for (FlightCrew row : flight.getCrew()) {
            if (row.isDeleted() || !crewTypeId.equals(row.getFlightCrewTypeId())) {
                continue;
            }
            return persons.findActiveById(row.getPersonId())
                    .map(p -> toRecipient(p, membershipForDeliveryClub(row.getPersonId())))
                    .orElse(null);
        }
        return null;
    }

    private static Recipient toRecipient(Person person, @Nullable PersonClub membership) {
        String memberNumber = membership == null ? null : membership.getMemberNumber();
        String name = (person.getFirstname() + " " + person.getLastname()).strip();
        return new Recipient(
                person.getId() == null ? null : person.getId().value(),
                memberNumber,
                name,
                person.getFirstname(),
                person.getLastname());
    }

    private Map<UUID, Short> crewTypeLegacyById() {
        Map<UUID, Short> byId = new HashMap<>();
        for (FlightCrewType type : flightCrewTypes.findAllByOrderByLegacyIntIdAsc()) {
            if (type.getId() != null) {
                byId.put(type.getId(), type.getLegacyIntId());
            }
        }
        return byId;
    }

    private Optional<FlightType> resolveFlightType(@Nullable UUID flightTypeId) {
        return flightTypeId == null ? Optional.empty() : flightTypes.findActiveById(flightTypeId);
    }

    private Optional<String> resolveLocationIcao(@Nullable UUID locationId) {
        if (locationId == null) {
            return Optional.empty();
        }
        return locations.findActiveById(locationId)
                .map(loc -> loc.getIcaoCode())
                .filter(icao -> icao != null);
    }

    private int resolveCostBalanceLegacyId(@Nullable UUID costBalanceTypeId) {
        if (costBalanceTypeId == null) {
            return 0;
        }
        return costBalanceTypes.findById(costBalanceTypeId)
                .map(FlightCostBalanceType::getLegacyIntId)
                .map(Short::intValue)
                .orElse(0);
    }

    private static long orZero(@Nullable Long value) {
        return value == null ? 0L : value;
    }

    private static @Nullable String asString(@Nullable UUID value) {
        return value == null ? null : value.toString();
    }

    private static @Nullable Integer asInteger(@Nullable Short value) {
        return value == null ? null : value.intValue();
    }
}
