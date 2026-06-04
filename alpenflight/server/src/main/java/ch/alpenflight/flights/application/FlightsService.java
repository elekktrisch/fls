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
 * <p>State-machine columns ({@code process_state_id}, {@code validated_on},
 * etc.) are stamped at create from {@link FlightInitialStateProvider};
 * transitions are owned by S-059. Air-state is computed (S-060), never
 * stored.
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
        // S-176 live-update nudge (J-3 T-05): publish the flights module's
        // FlightCreatedEvent so the me-module listener fans a "flight.created"
        // SSE to the creating principal's open dashboards once this
        // transaction commits (AFTER_COMMIT — matching the audit listener, so
        // the SSE only fires on a durably-committed flight). The Keycloak sub
        // is captured here on the request thread because AFTER_COMMIT runs
        // after the SecurityContext may have been cleared. The operating club
        // comes from the live tenant carrier, not saved.getOperatingClubId():
        // the @TenantId discriminator is stamped by Hibernate at flush and is
        // not reliably populated back onto the in-memory entity post-save.
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
        // limit + 1 sentinel to compute nextCursor cheaply.
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
        applyTowLink(flight, req);
        Flight saved = repository.save(flight);
        FlightDetail after = mapper.toDetail(saved);
        audit.record(AuditAction.UPDATE,
                AuditedTarget.updated("Flight",
                        Objects.requireNonNull(saved.getId()),
                        before, saved));
        return after;
    }

    /**
     * Club-admin dashboard counts (J-3 T-08): today's club flights +
     * flights-pending-validation, both tenant-scoped to the caller's club by
     * the {@code @TenantId} discriminator (ADR 0008 — the counts cannot cross
     * clubs). "Today" is derived from the injected {@link Clock} (same source
     * as {@link #newTemplate} / {@link #listFlights}) so the boundary is
     * testable with {@link Clock#fixed}; pending = {@code NotProcessed} +
     * {@code Invalid}. Counts ride {@code @TenantId} via JPQL {@code count}
     * queries — no native SQL, nothing to register.
     */
    @Transactional(readOnly = true)
    public ClubFlightCounts clubFlightCounts() {
        long today = repository.countByFlightDate(LocalDate.now(clock));
        long pending = repository.countByProcessStateIdIn(List.of(
                FlightProcessState.NOT_PROCESSED.id(),
                FlightProcessState.INVALID.id()));
        return new ClubFlightCounts(today, pending);
    }

    /**
     * Count of all non-deleted flights within the current effective tenant
     * (J-3 T-10). Tenant-scoped per call by the {@code @TenantId} discriminator
     * — it counts exactly the active tenant's flights. The sysadmin dashboard's
     * cross-tenant {@code totalFlights} is built by the {@code me} module
     * calling this once per club under {@code Tenants.runAs(clubId)} and summing
     * — the sanctioned cross-tenant read path (no native SQL).
     */
    @Transactional(readOnly = true)
    public long countAllFlights() {
        return repository.countAll();
    }

    /** Per-club defaults remain a future story; this stamps today's date + the discriminator. */
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

    /** See {@link FlightTemplateResponse}; cross-tenant source → 404. */
    @Transactional(readOnly = true)
    public FlightTemplateResponse copyTemplate(FlightId sourceId) {
        Flight flight = repository.findByIdWithCrew(sourceId)
                .orElseThrow(() -> new FlightNotFoundException(sourceId));
        return mapper.toCopyTemplate(flight);
    }

    /** AC-DIR-1; times are deliberately NOT returned. */
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
        // Legacy FlightService.cs:1314-1319. Application-layer only (no DB
        // cascade on the self-FK by design); each tow row gets its own audit
        // event sharing the request's actor + timestamp. Class-level
        // @Transactional means an exception in the tow leg rolls back the
        // glider delete too — caller sees an atomic outcome.
        if (flight.getFlightAircraftType() == FlightAircraftType.GLIDER
                && flight.getTowFlightId() != null) {
            FlightId towId = FlightId.of(flight.getTowFlightId());
            repository.findByIdWithCrew(towId).ifPresent(tow -> {
                if (tow.isDeleted()) {
                    return;
                }
                // The tow row may be in a terminal / admin-locked state
                // independent of the glider; cascade must honour the same
                // gate. Throwing here rolls back the glider delete too.
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

    /**
     * S-063 partial-PUT contract:
     * <ul>
     *   <li>{@code unlinkTowFlight=true} — unlink, regardless of any {@code
     *       towFlightId} value also sent (the explicit choice wins).</li>
     *   <li>{@code towFlightId} non-null — link to the resolved tow. Rejects
     *       if another non-deleted glider already references that tow row
     *       (cascade-delete would otherwise orphan the second link).</li>
     *   <li>Both absent / null — preserve the existing link. This is the
     *       partial-PUT fix: clients editing crew on a paired glider no
     *       longer have to re-echo the link to keep it.</li>
     * </ul>
     */
    private void applyTowLink(Flight flight, FlightUpdateRequest req) {
        if (Boolean.TRUE.equals(req.unlinkTowFlight())) {
            flight.unlinkTow();
            return;
        }
        if (req.towFlightId() == null) {
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

    /**
     * MVC-thread-bound — reads the request's authentication from Spring's
     * thread-local {@link SecurityContextHolder}. Reactive or {@code @Async}
     * call sites would see a {@code null} authentication and fail-closed
     * (returning {@code false} keeps the gate restrictive).
     */
    private static boolean callerIsClubAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_CLUB_ADMINISTRATOR".equals(a.getAuthority()));
    }

    /**
     * The creating principal's Keycloak {@code sub} off the request thread's
     * {@link SecurityContextHolder} (same source as {@link #callerIsClubAdmin}).
     * Captured at publish time onto {@link FlightCreatedEvent} so the
     * AFTER_COMMIT SSE listener delivers to the right stream even after the
     * context is cleared. Null for a non-JWT / system create (no dashboard to
     * nudge); the listener then no-ops.
     */
    private static @Nullable String currentSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return jwtAuth.getToken().getSubject();
        }
        return null;
    }
}
