package ch.alpenflight.flights.application;

import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightCrewItem;
import ch.alpenflight.flights.application.FlightDtos.FlightDetail;
import ch.alpenflight.flights.application.FlightDtos.FlightLastContextResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightListItem;
import ch.alpenflight.flights.application.FlightDtos.FlightTemplateResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightUpdateRequest;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrew;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.FlightCostBalanceTypeId;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Manual mapper between {@link Flight} (+ its aggregate-internal
 * {@link FlightCrew}) and the request / response DTOs. No reflection,
 * no MapStruct annotations — the explicit shape protects the mass-
 * assignment defense (state-machine columns are never wired through).
 */
@Component
public class FlightMapper {

    public FlightOperationalData toOperationalData(FlightCreateRequest req) {
        return new FlightOperationalData(
                req.flightDate(),
                req.startDateTime(),
                req.ldgDateTime(),
                req.blockStartDateTime(),
                req.blockEndDateTime(),
                unwrap(req.startLocationId()),
                unwrap(req.ldgLocationId()),
                req.startRunway(),
                req.ldgRunway(),
                req.outboundRoute(),
                req.inboundRoute(),
                unwrap(req.flightTypeId()),
                req.startTypeId(),
                req.nrOfLdgs(),
                req.nrOfLdgsOnStartLocation(),
                req.noStartTimeInformation(),
                req.noLdgTimeInformation(),
                req.engineStartOperatingCounterInSeconds(),
                req.engineEndOperatingCounterInSeconds(),
                req.comment(),
                req.incidentComment(),
                req.couponNumber(),
                unwrap(req.flightCostBalanceTypeId()),
                req.nrOfPassengers(),
                req.startPosition(),
                req.isSoloFlight());
    }

    public FlightOperationalData toOperationalData(FlightUpdateRequest req) {
        return new FlightOperationalData(
                req.flightDate(),
                req.startDateTime(),
                req.ldgDateTime(),
                req.blockStartDateTime(),
                req.blockEndDateTime(),
                unwrap(req.startLocationId()),
                unwrap(req.ldgLocationId()),
                req.startRunway(),
                req.ldgRunway(),
                req.outboundRoute(),
                req.inboundRoute(),
                unwrap(req.flightTypeId()),
                req.startTypeId(),
                req.nrOfLdgs(),
                req.nrOfLdgsOnStartLocation(),
                req.noStartTimeInformation(),
                req.noLdgTimeInformation(),
                req.engineStartOperatingCounterInSeconds(),
                req.engineEndOperatingCounterInSeconds(),
                req.comment(),
                req.incidentComment(),
                req.couponNumber(),
                unwrap(req.flightCostBalanceTypeId()),
                req.nrOfPassengers(),
                req.startPosition(),
                req.isSoloFlight());
    }

    public List<CrewMemberSpec> toCrewSpecs(@Nullable List<FlightCrewItem> crew) {
        if (crew == null || crew.isEmpty()) {
            return List.of();
        }
        return crew.stream()
                .map(c -> new CrewMemberSpec(
                        c.personId().value(),
                        c.flightCrewTypeId(),
                        c.beginFlightDatetime(),
                        c.endFlightDatetime(),
                        c.beginInstructionDatetime(),
                        c.endInstructionDatetime(),
                        c.nrOfLdgs(),
                        c.nrOfStarts()))
                .toList();
    }

    public FlightListItem toListItem(FlightRepository.ListRow row) {
        return new FlightListItem(
                FlightId.of(row.id()),
                row.flightAircraftType(),
                row.flightDate(),
                row.startDateTime(),
                row.ldgDateTime(),
                AircraftId.of(row.aircraftId()),
                row.processStateId(),
                row.airState());
    }

    public FlightDetail toDetail(Flight f) {
        return new FlightDetail(
                FlightId.of(Objects.requireNonNull(f.getId(), "flight id must not be null")),
                f.getFlightAircraftType(),
                AircraftId.of(f.getAircraftId()),
                f.getFlightDate(),
                f.getStartDateTime(),
                f.getLdgDateTime(),
                f.getBlockStartDateTime(),
                f.getBlockEndDateTime(),
                LocationId.ofNullable(f.getStartLocationId()),
                LocationId.ofNullable(f.getLdgLocationId()),
                f.getStartRunway(),
                f.getLdgRunway(),
                f.getOutboundRoute(),
                f.getInboundRoute(),
                FlightTypeId.ofNullable(f.getFlightTypeId()),
                f.isSoloFlight(),
                f.getStartTypeId(),
                FlightId.ofNullable(f.getTowFlightId()),
                f.getNrOfLdgs(),
                f.getNrOfLdgsOnStartLocation(),
                f.isNoStartTimeInformation(),
                f.isNoLdgTimeInformation(),
                f.airState(),
                f.getProcessStateId(),
                f.getVersion(),
                f.getEngineStartOperatingCounterInSeconds(),
                f.getEngineEndOperatingCounterInSeconds(),
                f.getComment(),
                f.getIncidentComment(),
                f.getCouponNumber(),
                FlightCostBalanceTypeId.ofNullable(f.getFlightCostBalanceTypeId()),
                f.getNrOfPassengers(),
                f.getStartPosition(),
                f.getCrew().stream()
                        .filter(c -> !c.isDeleted())
                        .map(FlightMapper::toCrewItem)
                        .toList());
    }

    /**
     * Builds a copy-template payload — preserves the operational shape but
     * clears identity-bearing + per-flight fields (timestamps, comments,
     * coupon, engine counters) per legacy {@code FlightsController.js:232-255}.
     * Crew is preserved with timestamps stripped (the new flight hasn't
     * happened yet).
     */
    public FlightTemplateResponse toCopyTemplate(Flight f) {
        return new FlightTemplateResponse(
                f.getFlightAircraftType(),
                AircraftId.of(f.getAircraftId()),
                f.getFlightDate(),
                LocationId.ofNullable(f.getStartLocationId()),
                LocationId.ofNullable(f.getLdgLocationId()),
                f.getStartRunway(),
                f.getLdgRunway(),
                f.getOutboundRoute(),
                f.getInboundRoute(),
                FlightTypeId.ofNullable(f.getFlightTypeId()),
                f.isSoloFlight(),
                f.getStartTypeId(),
                FlightId.ofNullable(f.getTowFlightId()),
                null, null,
                f.isNoStartTimeInformation(),
                f.isNoLdgTimeInformation(),
                FlightCostBalanceTypeId.ofNullable(f.getFlightCostBalanceTypeId()),
                f.getNrOfPassengers(),
                f.getStartPosition(),
                f.getCrew().stream()
                        .filter(c -> !c.isDeleted())
                        .map(FlightMapper::toCrewItemCleared)
                        .toList());
    }

    /**
     * Projects the last-flight context for the (aircraft, date) tuple per
     * AC-DIR-1. Times are deliberately omitted from the surface.
     */
    public FlightLastContextResponse toLastContext(Flight glider, @Nullable Flight tow) {
        FlightLastContextResponse.TowContext towCtx = null;
        if (tow != null) {
            towCtx = new FlightLastContextResponse.TowContext(
                    AircraftId.of(tow.getAircraftId()),
                    pickPilot(tow),
                    FlightTypeId.ofNullable(tow.getFlightTypeId()),
                    LocationId.ofNullable(tow.getLdgLocationId()));
        }
        return new FlightLastContextResponse(
                FlightTypeId.ofNullable(glider.getFlightTypeId()),
                pickPilot(glider),
                pickInvoiceRecipient(glider),
                LocationId.ofNullable(glider.getStartLocationId()),
                LocationId.ofNullable(glider.getLdgLocationId()),
                glider.getOutboundRoute(),
                glider.getInboundRoute(),
                glider.getStartTypeId(),
                FlightCostBalanceTypeId.ofNullable(glider.getFlightCostBalanceTypeId()),
                towCtx);
    }

    private static @Nullable PersonId pickPilot(Flight f) {
        return pickCrew(f, PILOT_OR_STUDENT);
    }

    private static @Nullable PersonId pickInvoiceRecipient(Flight f) {
        return pickCrew(f, FLIGHT_COST_INVOICE_RECIPIENT);
    }

    private static @Nullable PersonId pickCrew(Flight f, UUID crewTypeId) {
        for (FlightCrew c : f.getCrew()) {
            if (!c.isDeleted() && crewTypeId.equals(c.getFlightCrewTypeId())) {
                return PersonId.of(c.getPersonId());
            }
        }
        return null;
    }

    private static final UUID PILOT_OR_STUDENT =
            UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");
    private static final UUID FLIGHT_COST_INVOICE_RECIPIENT =
            UUID.fromString("019e2e15-2c00-76b6-8000-0000000036b6");

    private static FlightCrewItem toCrewItemCleared(FlightCrew c) {
        return new FlightCrewItem(
                PersonId.of(c.getPersonId()),
                c.getFlightCrewTypeId(),
                null, null, null, null,
                null, null);
    }

    private static FlightCrewItem toCrewItem(FlightCrew c) {
        return new FlightCrewItem(
                PersonId.of(c.getPersonId()),
                c.getFlightCrewTypeId(),
                c.getBeginFlightDatetime(),
                c.getEndFlightDatetime(),
                c.getBeginInstructionDatetime(),
                c.getEndInstructionDatetime(),
                c.getNrOfLdgs(),
                c.getNrOfStarts());
    }

    private static @Nullable UUID unwrap(@Nullable LocationId id) {
        return id == null ? null : id.value();
    }

    private static @Nullable UUID unwrap(@Nullable FlightTypeId id) {
        return id == null ? null : id.value();
    }

    private static @Nullable UUID unwrap(@Nullable FlightCostBalanceTypeId id) {
        return id == null ? null : id.value();
    }
}
