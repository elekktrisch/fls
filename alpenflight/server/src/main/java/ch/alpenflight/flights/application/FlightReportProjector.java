package ch.alpenflight.flights.application;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightReportDecorations;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flights.domain.FlightReportRowRepository;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.FlightSaved;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FlightReportProjector {

    private final FlightRepository flights;
    private final FlightReportRowRepository rows;
    private final FlightReportDecorations decorations;
    private final ClubTenantIdentifierResolver tenantResolver;

    public FlightReportProjector(FlightRepository flights,
                                 FlightReportRowRepository rows,
                                 FlightReportDecorations decorations,
                                 ClubTenantIdentifierResolver tenantResolver) {
        this.flights = flights;
        this.rows = rows;
        this.decorations = decorations;
        this.tenantResolver = tenantResolver;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onFlightSaved(FlightSaved event) {
        repairAround(event.flightId());
    }

    void repairAround(UUID savedId) {
        Set<UUID> affected = new LinkedHashSet<>();
        affected.add(savedId);
        flights.findByIdWithCrew(FlightId.of(savedId)).ifPresent(flight -> {
            if (flight.getTowFlightId() != null) {
                affected.add(flight.getTowFlightId());
            }
        });
        for (Flight glider : flights.findByTowFlightId(FlightId.of(savedId))) {
            UUID gliderId = glider.getId();
            if (gliderId != null) {
                affected.add(gliderId);
            }
        }
        affected.addAll(rows.findFlightIdsByTowFlightId(savedId));
        affected.addAll(rows.findFlightIdsByTowedGliderFlightId(savedId));

        for (UUID flightId : affected) {
            refresh(flightId);
        }
    }

    void refresh(UUID flightId) {
        Optional<Flight> loaded = flights.findByIdWithCrew(FlightId.of(flightId));
        if (loaded.isEmpty()) {
            rows.findByFlightId(flightId).ifPresent(rows::delete);
            return;
        }
        Flight flight = loaded.get();
        Flight tow = flight.getTowFlightId() == null ? null
                : flights.findByIdWithCrew(FlightId.of(flight.getTowFlightId())).orElse(null);
        UUID towedGliderFlightId = firstTowedGliderId(flightId);
        rows.findByFlightId(flightId).ifPresentOrElse(
                existing -> existing.refreshFrom(flight, tow, towedGliderFlightId, decorations),
                () -> rows.save(FlightReportRow.project(
                        flight, tow, towedGliderFlightId, decorations, resolveTenant())));
    }

    private @Nullable UUID firstTowedGliderId(UUID flightId) {
        for (Flight glider : flights.findByTowFlightId(FlightId.of(flightId))) {
            if (glider.getId() != null) {
                return glider.getId();
            }
        }
        return null;
    }

    private UUID resolveTenant() {
        return tenantResolver.resolveCurrentTenantIdentifier();
    }
}
