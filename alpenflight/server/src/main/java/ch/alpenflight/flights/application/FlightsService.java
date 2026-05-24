package ch.alpenflight.flights.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightDetail;
import ch.alpenflight.flights.application.FlightDtos.FlightListItem;
import ch.alpenflight.flights.application.FlightDtos.FlightListResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightUpdateRequest;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightInitialStateProvider;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.InvalidTowLinkException;
import ch.alpenflight.platform.id.FlightId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@link Flight} aggregate. Tenant scoping
 * is structural via Hibernate's {@code @TenantId} discriminator on
 * {@link Flight#getOperatingClubId()}; role-within-tenant gates live on
 * the controller.
 *
 * <p>S-058 (reverts S-159): Aircraft is cross-tenant — any active aircraft
 * may be referenced on a Flight (charter case: Club B flies Club A's tow
 * plane). The FK constraint at the DB rejects unknown aircraftIds with a
 * generic data-integrity violation; pre-validation isn't needed for the
 * tenant gate (Aircraft has no @TenantId), only for friendlier error
 * messages, which we defer.
 *
 * <p>State-machine columns ({@code process_state_id}, {@code air_state_id},
 * {@code validated_on}, etc.) are stamped at create from
 * {@link FlightInitialStateProvider}; transitions are deferred to S-059.
 *
 * <p>Audit emission: every mutation calls {@link AuditTrail#record}. Flight
 * + FlightCrew are in {@code audit.redaction.deny-all} for now — the
 * editable surface includes PII (comment / route / incident); an allow-list
 * is a follow-up security-engineer story.
 */
@Service
@Transactional
public class FlightsService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_WINDOW_DAYS = 90;

    private final FlightRepository repository;
    private final FlightInitialStateProvider initialState;
    private final FlightMapper mapper;
    private final AuditTrail audit;
    private final Clock clock;

    public FlightsService(FlightRepository repository,
                          FlightInitialStateProvider initialState,
                          FlightMapper mapper,
                          AuditTrail audit,
                          Clock clock) {
        this.repository = repository;
        this.initialState = initialState;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    public FlightDetail createFlight(FlightCreateRequest req) {
        UUID aircraftUuid = req.aircraftId().value();
        FlightOperationalData ops = mapper.toOperationalData(req);
        Flight flight = switch (req.flightAircraftType()) {
            case GLIDER -> Flight.createGlider(aircraftUuid,
                    initialState.initialProcessStateId(),
                    initialState.initialAirStateId(),
                    ops);
            case TOW -> Flight.createTow(aircraftUuid,
                    initialState.initialProcessStateId(),
                    initialState.initialAirStateId(),
                    ops);
            case MOTOR -> Flight.createMotor(aircraftUuid,
                    initialState.initialProcessStateId(),
                    initialState.initialAirStateId(),
                    ops);
        };
        flight.replaceCrew(mapper.toCrewSpecs(req.crew()));
        Flight saved = repository.save(flight);
        FlightDetail detail = mapper.toDetail(saved);
        audit.record(AuditAction.CREATE,
                AuditedTarget.created("Flight",
                        Objects.requireNonNull(saved.getId()),
                        saved));
        return detail;
    }

    @Transactional(readOnly = true)
    public FlightDetail getFlight(FlightId id) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        return mapper.toDetail(flight);
    }

    @Transactional(readOnly = true)
    public FlightListResponse listFlights(@Nullable LocalDate from,
                                          @Nullable LocalDate to,
                                          @Nullable String cursor,
                                          @Nullable Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT
                : Math.min(Math.max(1, requestedLimit), MAX_LIMIT);
        LocalDate effectiveFrom = from;
        LocalDate effectiveTo = to;
        if (effectiveFrom == null && effectiveTo == null) {
            LocalDate today = LocalDate.now(clock);
            effectiveTo = today;
            effectiveFrom = today.minusDays(DEFAULT_WINDOW_DAYS);
        }
        FlightListCursor decoded = cursor == null ? null : FlightListCursor.decode(cursor);
        LocalDate cursorDate = decoded == null ? null : decoded.flightDate();
        UUID cursorId = decoded == null ? null : decoded.id();
        // limit + 1 sentinel to compute nextCursor cheaply.
        List<FlightRepository.ListRow> rows = repository.findListWindow(
                effectiveFrom, effectiveTo, cursorDate, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<FlightRepository.ListRow> page = hasMore ? rows.subList(0, limit) : rows;
        List<FlightListItem> items = new ArrayList<>(page.size());
        for (FlightRepository.ListRow row : page) {
            items.add(mapper.toListItem(row));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            FlightRepository.ListRow last = page.get(page.size() - 1);
            nextCursor = new FlightListCursor(last.flightDate(), last.id()).encode();
        }
        return new FlightListResponse(items, nextCursor);
    }

    public FlightDetail updateFlight(FlightId id, FlightUpdateRequest req) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        FlightDetail before = mapper.toDetail(flight);
        flight.repointAircraft(req.aircraftId().value());
        flight.updateOperationalData(mapper.toOperationalData(req));
        flight.replaceCrew(mapper.toCrewSpecs(req.crew()));
        if (req.towFlightId() == null) {
            flight.unlinkTow();
        } else {
            FlightId towId = req.towFlightId();
            Flight tow = repository.findByIdWithCrew(towId)
                    .orElseThrow(() -> new InvalidTowLinkException(
                            "Tow flight " + towId.toExternal() + " not found in current tenant"));
            flight.linkTow(tow);
        }
        Flight saved = repository.save(flight);
        FlightDetail after = mapper.toDetail(saved);
        audit.record(AuditAction.UPDATE,
                AuditedTarget.updated("Flight",
                        Objects.requireNonNull(saved.getId()),
                        before, saved));
        return after;
    }

    public void softDeleteFlight(FlightId id) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        FlightDetail before = mapper.toDetail(flight);
        flight.softDelete(clock.instant());
        repository.save(flight);
        audit.record(AuditAction.DELETE,
                AuditedTarget.deleted("Flight",
                        Objects.requireNonNull(flight.getId()),
                        before));
    }

}
