package ch.alpenflight.flights.application;

import ch.alpenflight.aircraft.domain.AircraftSaved;
import ch.alpenflight.flights.domain.FlightReportRowRepository;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flighttypes.domain.FlightTypeSaved;
import ch.alpenflight.locations.domain.LocationSaved;
import ch.alpenflight.persons.domain.PersonSaved;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FlightReportDecorationRefreshListener {

    private final FlightRepository flights;
    private final FlightReportRowRepository rows;
    private final FlightReportProjector projector;

    public FlightReportDecorationRefreshListener(FlightRepository flights,
                                                 FlightReportRowRepository rows,
                                                 FlightReportProjector projector) {
        this.flights = flights;
        this.rows = rows;
        this.projector = projector;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAircraftSaved(AircraftSaved event) {
        repairAll(flights.findIdsByAircraftId(event.aircraftId()));
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onPersonSaved(PersonSaved event) {
        repairAll(rows.findFlightIdsByCrewPersonId(event.personId()));
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onLocationSaved(LocationSaved event) {
        repairAll(flights.findIdsByLocationId(event.locationId()));
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onFlightTypeSaved(FlightTypeSaved event) {
        repairAll(flights.findIdsByFlightTypeId(event.flightTypeId()));
    }

    private void repairAll(List<UUID> affectedFlightIds) {
        for (UUID flightId : affectedFlightIds) {
            projector.repairAround(flightId);
        }
    }
}
