package ch.alpenflight.reservations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationCreateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationDetail;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationListItem;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationPage;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationPageRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationTypeListItem;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationUpdateRequest;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationNotFoundException;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.Range;
import ch.alpenflight.reservations.domain.ReservationConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@link AircraftReservation} aggregate (J-5
 * T-05). Mirrors {@code AircraftsService}: a {@code @Transactional} service over
 * the domain port, mapping aggregate ↔ DTO and emitting an {@link AuditTrail}
 * event per mutation.
 *
 * <p><strong>Conflict guard (net-new, J-5 assumption #1).</strong> Create +
 * update call {@link AircraftReservationRepository#existsActiveConflict} on the
 * effective span; on update the reservation's own id is passed as
 * {@code excludeId} so an edit-in-place does not conflict with itself. A hit
 * raises {@link ReservationConflictException} → 409.
 *
 * <p><strong>Duration guard (net-new, assumption #2).</strong> The aggregate's
 * {@code create} / {@code reschedule} run {@code validateDuration()} at
 * construction, so a timed reservation whose end is not after its start throws
 * {@code InvalidReservationDurationException} → 422 before the conflict probe.
 *
 * <p>The conflict probe resolves the tenant itself (T-04); the create path also
 * resolves the tenant here to stamp {@code operating_club_id} on the new
 * aggregate (legacy-open: the aircraft FK may cross tenants, the reservation is
 * stamped with the operating club).
 */
@Service
@Transactional
public class AircraftReservationsService {

    /**
     * Audit entity-type string — keyed in {@code application.yml}
     * {@code audit.redaction.entities.AircraftReservation} (default-deny for the
     * free-text {@code remarks} / PII-adjacent person ids).
     */
    private static final String AUDIT_ENTITY_TYPE = "AircraftReservation";

    /** Paged-list guards: a {@code size ≤ 0} falls back to this; oversize is clamped. */
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    private final AircraftReservationRepository reservations;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public AircraftReservationsService(AircraftReservationRepository reservations,
                                       ClubTenantIdentifierResolver tenantResolver,
                                       Clock clock,
                                       AuditTrail auditTrail) {
        this.reservations = reservations;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public AircraftReservationDetail getReservation(UUID id) {
        return AircraftReservationMapper.toDetail(loadOrThrow(id));
    }

    public AircraftReservationDetail createReservation(AircraftReservationCreateRequest req) {
        requireTypeReference(req.reservationTypeId(), req.flightTypeId());
        UUID operatingClubId = resolveTenantOrThrow();
        // validateDuration() runs at construction → InvalidReservationDurationException (422).
        AircraftReservation r = AircraftReservation.create(
                operatingClubId,
                req.aircraftId(),
                req.pilotPersonId(),
                req.locationId(),
                req.reservationTypeId(),
                req.flightTypeId(),
                req.start(),
                req.end(),
                req.isAllDay(),
                req.secondCrewPersonId(),
                req.remarks());
        rejectIfConflicting(r, null);
        AircraftReservationDetail created = AircraftReservationMapper.toDetail(reservations.save(r));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id(), created));
        return created;
    }

    public AircraftReservationDetail updateReservation(UUID id,
                                                       AircraftReservationUpdateRequest req) {
        requireTypeReference(req.reservationTypeId(), req.flightTypeId());
        AircraftReservation r = loadOrThrow(id);
        AircraftReservationDetail before = AircraftReservationMapper.toDetail(r);

        r.changeAircraft(req.aircraftId());
        r.changeCrew(req.pilotPersonId(), req.secondCrewPersonId());
        r.reassignLocation(req.locationId());
        r.changeType(req.reservationTypeId(), req.flightTypeId());
        r.updateInfo(req.remarks());
        // reschedule re-runs validateDuration() → InvalidReservationDurationException (422).
        r.reschedule(req.start(), req.end(), req.isAllDay());

        // Self-excluded on update: the row being edited is excluded from the
        // conflict probe so an in-place reschedule does not collide with itself.
        rejectIfConflicting(r, id);
        AircraftReservationDetail after = AircraftReservationMapper.toDetail(reservations.save(r));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id, before, after));
        return after;
    }

    public void deleteReservation(UUID id, @Nullable UUID userId) {
        AircraftReservation r = loadOrThrow(id);
        AircraftReservationDetail before = AircraftReservationMapper.toDetail(r);
        r.softDelete(userId, clock);
        reservations.save(r);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
    }

    /**
     * One SPA page of the tenant's active reservations (J-5 T-06). Honours the
     * legacy-shaped {@code sorting} (only {@code start: asc|desc} — default asc)
     * + a basic {@code searchFilter} date-range on the reservation start. The
     * {@code totalRows} is the unpaged count of the same predicate so the SPA
     * can render pagination in one round-trip.
     */
    @Transactional(readOnly = true)
    public AircraftReservationPage page(int pageStart, int pageSize,
                                        @Nullable AircraftReservationPageRequest request) {
        int safeStart = Math.max(pageStart, 0);
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        boolean ascending = sortStartAscending(request);
        var from = filterFrom(request);
        var to = filterTo(request);

        List<AircraftReservationListItem> items = reservations
                .findActiveListPage(from, to, ascending, safeStart, safeSize).stream()
                .map(AircraftReservationMapper::toListItem)
                .toList();
        long total = reservations.countActiveList(from, to);
        return new AircraftReservationPage(items, safeStart, safeSize, total);
    }

    /** Future reservations (start ≥ now) — the scheduler/table default ({@code /future}). */
    @Transactional(readOnly = true)
    public List<AircraftReservationListItem> listFuture() {
        return reservations.findFutureListRows(clock.instant()).stream()
                .map(AircraftReservationMapper::toListItem)
                .toList();
    }

    /** Reservations overlapping the UTC day {@code [date 00:00, date+1 00:00)} ({@code /day/{date}}). */
    @Transactional(readOnly = true)
    public List<AircraftReservationListItem> listForDay(java.time.LocalDate date) {
        Instant dayStart = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return reservations.findActiveListRowsForDay(dayStart, dayEnd).stream()
                .map(AircraftReservationMapper::toListItem)
                .toList();
    }

    private static boolean sortStartAscending(@Nullable AircraftReservationPageRequest request) {
        if (request == null || request.sorting() == null) {
            return true;
        }
        String dir = request.sorting().get("start");
        // Default asc; only an explicit "desc" flips it (case-insensitive).
        return dir == null || !"desc".equalsIgnoreCase(dir.trim());
    }

    private static @Nullable Instant filterFrom(@Nullable AircraftReservationPageRequest request) {
        return request == null || request.searchFilter() == null
                ? null : request.searchFilter().from();
    }

    private static @Nullable Instant filterTo(@Nullable AircraftReservationPageRequest request) {
        return request == null || request.searchFilter() == null
                ? null : request.searchFilter().to();
    }

    @Transactional(readOnly = true)
    public List<AircraftReservationTypeListItem> listReservationTypes() {
        return reservations.findActiveTypeListItems().stream()
                .map(AircraftReservationMapper::toTypeListItem)
                .toList();
    }

    private void rejectIfConflicting(AircraftReservation r, @Nullable UUID excludeId) {
        UUID aircraftId = java.util.Objects.requireNonNull(r.getAircraftId(), "aircraftId");
        Range window = new Range(r.effectiveStart(), r.effectiveEnd());
        if (reservations.existsActiveConflict(aircraftId, window, excludeId)) {
            throw new ReservationConflictException(
                    "Reservation overlaps an existing booking on aircraft " + aircraftId);
        }
    }

    private static void requireTypeReference(@Nullable UUID reservationTypeId,
                                             @Nullable UUID flightTypeId) {
        if (reservationTypeId == null && flightTypeId == null) {
            throw new IllegalArgumentException(
                    "a reservation-type reference is required: set reservationTypeId or flightTypeId");
        }
    }

    private UUID resolveTenantOrThrow() {
        UUID tenant = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
            throw new IllegalStateException(
                    "AircraftReservation.create requires a tenant context; unscoped caller cannot reserve");
        }
        return tenant;
    }

    private AircraftReservation loadOrThrow(UUID id) {
        return reservations.findActiveById(id)
                .orElseThrow(() -> new AircraftReservationNotFoundException(id));
    }
}
