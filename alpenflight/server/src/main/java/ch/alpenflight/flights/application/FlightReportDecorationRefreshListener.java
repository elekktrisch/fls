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

/**
 * Rename propagation for the flight-report read-model (J-7 RM-2, ADR 0027
 * §2): the denormalized decoration strings (immatriculation, crew names,
 * location names, flight-type name / code) must follow their source aggregate.
 * Each source publishes a {@code *Saved} domain event from its own module's
 * domain package on every repository save (the Flight / Deployment
 * {@code @DomainEvents} precedent); this sibling of
 * {@link FlightReportProjector} listens synchronously — same transaction, no
 * eventual-consistency window — resolves the affected flights through
 * production-side lookups, and re-projects WHOLE rows through the projector's
 * {@link FlightReportProjector#repairAround} seam. Strings are never patched
 * in place: the same person can sit on a row as pilot, second crew, or tow
 * pilot, and tow-block copies live on OTHER flights' rows — re-projection
 * through the one projection path covers all of it (the tow expansion inside
 * {@code repairAround} carries a renamed source from a tow flight onto the
 * glider rows referencing it).
 *
 * <p>Tenant scope: every per-flight read and row write rides {@code @TenantId}
 * (ADR 0008), so the refresh repairs the mutating principal's club. For the
 * cross-tenant sources (Person / Aircraft carry no {@code @TenantId}), rows
 * of OTHER clubs referencing the renamed aggregate — the charter-aircraft /
 * shared-person case — are structurally out of reach inside this transaction
 * (the session's tenant is fixed at session-open) and keep the old string
 * until that club's next flight save or {@link FlightReportRebuildService}
 * run. Accepted RM-2 posture; surfaced in the RM-2 report.
 */
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

    /**
     * Aircraft rename → flights flying it. {@code repairAround} additionally
     * refreshes the glider rows whose tow block copies a renamed TOW
     * aircraft's immatriculation (reverse lookup via the row's
     * {@code tow_flight_id}).
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAircraftSaved(AircraftSaved event) {
        repairAll(flights.findIdsByAircraftId(event.aircraftId()));
    }

    /**
     * Person rename → flights carrying the person as live crew, found via the
     * read-model's own {@code t_flight_report_crew} child ({@code
     * ix_frc_person}). The child is not tenant-discriminated; out-of-tenant
     * ids no-op inside {@code repairAround} (every read there is
     * tenant-scoped).
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onPersonSaved(PersonSaved event) {
        repairAll(rows.findFlightIdsByCrewPersonId(event.personId()));
    }

    /** Location rename → flights starting or landing there (tenant-local by construction). */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onLocationSaved(LocationSaved event) {
        repairAll(flights.findIdsByLocationId(event.locationId()));
    }

    /** FlightType rename → flights of that type (tenant-local by construction). */
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
