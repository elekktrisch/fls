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

/**
 * Synchronous flight-report read-model sync (ADR 0027 §2). Listens to
 * {@link FlightSaved} — published by Spring Data on EVERY
 * {@link FlightRepository#save} via {@code @DomainEvents} — and refreshes the
 * affected {@link FlightReportRow}s in the SAME transaction (plain
 * {@code @EventListener}, deliberately NOT {@code AFTER_COMMIT}: the
 * read-model must commit or roll back atomically with the write model — no
 * eventual-consistency window).
 *
 * <p>One save can affect up to four rows: the saved flight's own row, its
 * newly-linked tow flight's row (reverse {@code towed_glider_flight_id}), and
 * any rows still carrying a now-stale link to the saved flight (found via the
 * read-model's own reverse columns — the only place the PREVIOUS link
 * survives once the aggregate has moved on). Each affected row is rebuilt
 * from the current aggregate state, so the repair is idempotent.
 *
 * <p>A flight invisible to the tenant-scoped repository (soft-deleted) has
 * its row deleted — read-model rows exist only for live flights.
 *
 * <p>Tenant: row inserts are stamped by Hibernate's {@code @TenantId}
 * resolver exactly like the Flight insert in the same session; the crew
 * children copy the resolved tenant explicitly (they are aggregate-internal
 * and carry no own discriminator).
 */
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

    /**
     * {@code REQUIRED}: joins the surrounding service transaction on every
     * production path (same-transaction sync per ADR 0027); test-side
     * repository-direct saves without an outer transaction get a dedicated
     * one so the multi-row repair stays atomic.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onFlightSaved(FlightSaved event) {
        UUID savedId = event.flightId();
        Set<UUID> affected = new LinkedHashSet<>();
        affected.add(savedId);
        // Forward: the saved flight's current tow link → that tow's row
        // carries the towed_glider back-reference.
        flights.findByIdWithCrew(FlightId.of(savedId)).ifPresent(flight -> {
            if (flight.getTowFlightId() != null) {
                affected.add(flight.getTowFlightId());
            }
        });
        // Forward-reverse: gliders currently linking the saved flight as
        // their tow → their rows carry the saved flight's tow block.
        for (Flight glider : flights.findByTowFlightId(FlightId.of(savedId))) {
            UUID gliderId = glider.getId();
            if (gliderId != null) {
                affected.add(gliderId);
            }
        }
        // Stale-link repair: rows that STILL reference the saved flight from
        // a previous state (unlink, re-link, soft-delete) — only the
        // read-model remembers the old link.
        affected.addAll(rows.findFlightIdsByTowFlightId(savedId));
        affected.addAll(rows.findFlightIdsByTowedGliderFlightId(savedId));

        for (UUID flightId : affected) {
            refresh(flightId);
        }
    }

    /** Rebuilds (or deletes) the read-model row for one flight id. */
    private void refresh(UUID flightId) {
        Optional<Flight> loaded = flights.findByIdWithCrew(FlightId.of(flightId));
        if (loaded.isEmpty()) {
            // Soft-deleted (or otherwise invisible) — the read-model carries
            // live flights only.
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

    /** Oracle parity: the (first) live glider whose tow link points here. */
    private @Nullable UUID firstTowedGliderId(UUID flightId) {
        for (Flight glider : flights.findByTowFlightId(FlightId.of(flightId))) {
            if (glider.getId() != null) {
                return glider.getId();
            }
        }
        return null;
    }

    /**
     * The effective tenant for crew-child stamping — same resolver Hibernate's
     * {@code @TenantId} discriminator consults in this session, so the copied
     * value can never diverge from the row's stamped discriminator. Under the
     * {@code NO_TENANT} sentinel the insert fails closed at the club FK.
     */
    private UUID resolveTenant() {
        return tenantResolver.resolveCurrentTenantIdentifier();
    }
}
