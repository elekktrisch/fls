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
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationValidateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.ReservationValidationResult;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservation.EffectiveSpan;
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

@Service
@Transactional
public class AircraftReservationsService {

    private static final String AUDIT_ENTITY_TYPE = "AircraftReservation";

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
        AircraftReservation r = AircraftReservation.create(
                operatingClubId,
                req.aircraftId().value(),
                req.pilotPersonId().value(),
                req.locationId().value(),
                req.reservationTypeId(),
                req.flightTypeId(),
                req.start(),
                req.end(),
                req.isAllDay(),
                req.secondCrewPersonId() == null ? null : req.secondCrewPersonId().value(),
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

        r.changeAircraft(req.aircraftId().value());
        r.changeCrew(req.pilotPersonId().value(),
                req.secondCrewPersonId() == null ? null : req.secondCrewPersonId().value());
        r.reassignLocation(req.locationId().value());
        r.changeType(req.reservationTypeId(), req.flightTypeId());
        r.updateInfo(req.remarks());
        r.reschedule(req.start(), req.end(), req.isAllDay());

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

    @Transactional(readOnly = true)
    public List<AircraftReservationListItem> listFuture() {
        return reservations.findFutureListRows(clock.instant()).stream()
                .map(AircraftReservationMapper::toListItem)
                .toList();
    }

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

    @Transactional(readOnly = true)
    public ReservationValidationResult validateOverlap(AircraftReservationValidateRequest req) {
        EffectiveSpan span = AircraftReservation.effectiveSpan(
                req.start(), req.end(), req.isAllDay());
        if (!span.isValidDuration()) {
            return ReservationValidationResult.failed(
                    "start", "Reservation end must be after start.");
        }
        Range window = new Range(span.start(), span.end());
        if (reservations.existsActiveConflict(
                req.aircraftId().value(), window, req.excludeReservationId())) {
            return ReservationValidationResult.failed(
                    "start", "This aircraft is already reserved for an overlapping time.");
        }
        return ReservationValidationResult.passed();
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
