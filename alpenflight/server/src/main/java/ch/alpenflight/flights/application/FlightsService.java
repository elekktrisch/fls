package ch.alpenflight.flights.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightDetail;
import ch.alpenflight.flights.application.FlightDtos.FlightLastContextResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightListItem;
import ch.alpenflight.flights.application.FlightDtos.FlightListResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightTemplateResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightUpdateRequest;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightInitialStateProvider;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.FlightStateGateException;
import ch.alpenflight.flights.domain.FlightVersionMismatchException;
import ch.alpenflight.flights.domain.InvalidTowLinkException;
import ch.alpenflight.flights.domain.TowLinkPolicy;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FlightsService(FlightRepository repository,
                          FlightInitialStateProvider initialState,
                          FlightMapper mapper,
                          AuditTrail audit,
                          ApplicationEventPublisher events,
                          Clock clock) {
        this.repository = repository;
        this.initialState = initialState;
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public FlightDetail createFlight(FlightCreateRequest req) {
        UUID aircraftUuid = req.aircraftId().value();
        FlightOperationalData ops = mapper.toOperationalData(req);
        Flight flight = switch (req.flightAircraftType()) {
            case GLIDER -> Flight.createGlider(aircraftUuid,
                    initialState.initialProcessStateId(), ops);
            case TOW -> Flight.createTow(aircraftUuid,
                    initialState.initialProcessStateId(), ops);
            case MOTOR -> Flight.createMotor(aircraftUuid,
                    initialState.initialProcessStateId(), ops);
        };
        flight.replaceCrew(mapper.toCrewSpecs(req.crew()));
        Flight saved = repository.save(flight);
        FlightDetail detail = mapper.toDetail(saved);
        UUID flightId = Objects.requireNonNull(saved.getId());
        audit.record(AuditAction.CREATE,
                AuditedTarget.created("Flight", flightId, saved));
        UUID operatingClub = TenantContextCarrier.current().orElse(null);
        events.publishEvent(new FlightCreatedEvent(flightId, operatingClub, currentSub()));
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
                                          @Nullable Integer requestedLimit,
                                          @Nullable UUID personId) {
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
        List<FlightRepository.ListRow> rows = repository.findListWindow(
                effectiveFrom, effectiveTo, cursorDate, cursorId, limit + 1, personId);
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

    public FlightDetail updateFlight(FlightId id, FlightUpdateRequest req,
                                     @Nullable Long ifMatchVersion) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        if (ifMatchVersion != null && ifMatchVersion != flight.getVersion()) {
            throw new FlightVersionMismatchException(ifMatchVersion, flight.getVersion());
        }
        assertMutationAllowed(flight);
        FlightDetail before = mapper.toDetail(flight);
        flight.repointAircraft(req.aircraftId().value());
        flight.updateOperationalData(mapper.toOperationalData(req));
        flight.replaceCrew(mapper.toCrewSpecs(req.crew()));
        applyTowLinkPreservingLinkWhenRequestOmitsIt(flight, req);
        Flight saved = repository.save(flight);
        FlightDetail after = mapper.toDetail(saved);
        audit.record(AuditAction.UPDATE,
                AuditedTarget.updated("Flight",
                        Objects.requireNonNull(saved.getId()),
                        before, saved));
        return after;
    }

    @Transactional(readOnly = true)
    public ClubFlightCounts clubFlightCounts() {
        long today = repository.countByFlightDate(LocalDate.now(clock));
        long pending = repository.countByProcessStateIdIn(List.of(
                FlightProcessState.NOT_PROCESSED.id(),
                FlightProcessState.INVALID.id()));
        return new ClubFlightCounts(today, pending);
    }

    @Transactional(readOnly = true)
    // RENAME: countAllFlights -> countFlightsInCurrentTenant
    public long countAllFlights() {
        return repository.countAll();
    }

    @Transactional(readOnly = true)
    public FlightTemplateResponse newTemplate(FlightAircraftType type) {
        return new FlightTemplateResponse(
                type,
                null,
                LocalDate.now(clock),
                null, null,
                null, null,
                null, null,
                null,
                false,
                null,
                null,
                null, null,
                false, false,
                null,
                null, null,
                List.of());
    }

    @Transactional(readOnly = true)
    public FlightTemplateResponse copyTemplate(FlightId sourceId) {
        Flight flight = repository.findByIdWithCrew(sourceId)
                .orElseThrow(() -> new FlightNotFoundException(sourceId));
        return mapper.toCopyTemplate(flight);
    }

    @Transactional(readOnly = true)
    public Optional<FlightLastContextResponse> lastContext(UUID aircraftId,
                                                           LocalDate flightDate) {
        return repository.findLastByAircraftAndDate(aircraftId, flightDate)
                .map(flight -> {
                    Flight tow = null;
                    if (flight.getTowFlightId() != null) {
                        tow = repository.findByIdWithCrew(FlightId.of(flight.getTowFlightId()))
                                .orElse(null);
                    }
                    return mapper.toLastContext(flight, tow);
                });
    }

    public void softDeleteFlight(FlightId id, @Nullable Long ifMatchVersion) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        if (ifMatchVersion != null && ifMatchVersion != flight.getVersion()) {
            throw new FlightVersionMismatchException(ifMatchVersion, flight.getVersion());
        }
        assertMutationAllowed(flight);
        FlightDetail before = mapper.toDetail(flight);
        flight.softDelete(clock.instant());
        repository.save(flight);
        audit.record(AuditAction.DELETE,
                AuditedTarget.deleted("Flight",
                        Objects.requireNonNull(flight.getId()),
                        before));
        if (flight.getFlightAircraftType() == FlightAircraftType.GLIDER
                && flight.getTowFlightId() != null) {
            FlightId towId = FlightId.of(flight.getTowFlightId());
            repository.findByIdWithCrew(towId).ifPresent(tow -> {
                if (tow.isDeleted()) {
                    return;
                }
                assertMutationAllowed(tow);
                FlightDetail towBefore = mapper.toDetail(tow);
                tow.softDelete(clock.instant());
                repository.save(tow);
                audit.record(AuditAction.DELETE,
                        AuditedTarget.deleted("Flight",
                                Objects.requireNonNull(tow.getId()),
                                towBefore));
            });
        }
    }

    private void applyTowLinkPreservingLinkWhenRequestOmitsIt(Flight flight,
                                                              FlightUpdateRequest req) {
        boolean explicitUnlinkWinsOverAnyTowFlightId = Boolean.TRUE.equals(req.unlinkTowFlight());
        if (explicitUnlinkWinsOverAnyTowFlightId) {
            flight.unlinkTow();
            return;
        }
        boolean requestOmitsTowLink = req.towFlightId() == null;
        if (requestOmitsTowLink) {
            return;
        }
        FlightId towId = req.towFlightId();
        Flight tow = repository.findByIdWithCrew(towId)
                .orElseThrow(() -> new InvalidTowLinkException(
                        "Tow flight " + towId.toExternal() + " not found in current tenant"));
        TowLinkPolicy.verifyExclusiveLink(towId, repository.findByTowFlightId(towId), flight);
        flight.linkTow(tow);
    }

    private static void assertMutationAllowed(Flight flight) {
        FlightProcessState state = flight.getProcessState();
        if (state == FlightProcessState.DELIVERY_BOOKED) {
            throw new FlightStateGateException(state,
                    FlightStateGateException.Reason.TERMINAL);
        }
        if (isAdminLockedState(state) && !callerIsClubAdmin()) {
            throw new FlightStateGateException(state,
                    FlightStateGateException.Reason.ADMIN_REQUIRED);
        }
    }

    private static boolean isAdminLockedState(FlightProcessState state) {
        return state == FlightProcessState.LOCKED
                || state == FlightProcessState.DELIVERY_PREPARED
                || state == FlightProcessState.DELIVERY_PREPARATION_ERROR
                || state == FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS;
    }

    private static boolean callerIsClubAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_CLUB_ADMINISTRATOR".equals(a.getAuthority()));
    }

    private static @Nullable String currentSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return jwtAuth.getToken().getSubject();
        }
        return null;
    }
}
