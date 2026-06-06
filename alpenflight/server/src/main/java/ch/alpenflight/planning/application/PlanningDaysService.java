package ch.alpenflight.planning.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayCreateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayDetail;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPage;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPageRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayUpdateRequest;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.planning.domain.PlanningDayAssignmentTypeRepository;
import ch.alpenflight.planning.domain.PlanningDayNotFoundException;
import ch.alpenflight.planning.domain.PlanningDayRepository;
import ch.alpenflight.planning.domain.PlanningDayRepository.ListRow;
import ch.alpenflight.planning.domain.PlanningRole;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@link PlanningDay} aggregate (J-6 T-04).
 * Mirrors {@code AircraftReservationsService}: a {@code @Transactional} service
 * over the domain port, mapping aggregate ↔ DTO and emitting an {@link
 * AuditTrail} event per mutation.
 *
 * <p><strong>Crew mapping (the load-bearing shape decision).</strong> The wire
 * DTO carries three fixed crew pickers but storage is the generic
 * typed-assignment model. {@link #roleTypeIds()} resolves each well-known
 * {@link PlanningRole} to its per-club {@link PlanningDayAssignmentType} id (by
 * the type's well-known German name); the write path upserts/clears each role's
 * assignment row via {@code PlanningDay.assignRole}, and the read path resolves
 * each assignment back to a role to populate the three nullable person-id slots.
 *
 * <p><strong>Dedup → 409.</strong> The repository's dedup-aware save surfaces a
 * duplicate {@code (club, date, location)} as the catchable
 * {@code PlanningDayConflictException} (→ 409 in the web layer), never a raw
 * constraint-violation 500.
 *
 * <p><strong>Permission gate.</strong> Update / delete are gated to a
 * {@code CLUB_ADMINISTRATOR} OR the record's creator (legacy
 * {@code PlanningDayService.cs:407-425}); a caller who is neither gets an
 * {@link AccessDeniedException} (→ 403). The {@code canUpdateRecord} /
 * {@code canDeleteRecord} flags on every detail DTO surface the same gate to the
 * UI (delete: admin-or-creator; update: admin-or-creator, matching legacy).
 */
@Service
@Transactional
public class PlanningDaysService {

    /**
     * Audit entity-type string. The {@code planning.domain} package is not in
     * the audit-redaction coverage roots, so its DTO snapshot default-denies on
     * the redaction walk — PII-safe without an explicit allow-list.
     */
    private static final String AUDIT_ENTITY_TYPE = "PlanningDay";

    /** Legacy default paged-list size (100). A non-positive size falls back to this. */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private static final String ROLE_SYSTEM_ADMIN = "ROLE_SYSTEM_ADMINISTRATOR";
    private static final String ROLE_CLUB_ADMIN = "ROLE_CLUB_ADMINISTRATOR";

    private final PlanningDayRepository planningDays;
    private final PlanningDayAssignmentTypeRepository assignmentTypes;
    private final UserPrincipalLookup principals;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public PlanningDaysService(PlanningDayRepository planningDays,
                               PlanningDayAssignmentTypeRepository assignmentTypes,
                               UserPrincipalLookup principals,
                               ClubTenantIdentifierResolver tenantResolver,
                               Clock clock,
                               AuditTrail auditTrail) {
        this.planningDays = planningDays;
        this.assignmentTypes = assignmentTypes;
        this.principals = principals;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public PlanningDayDetail getPlanningDay(UUID id) {
        return toDetail(loadOrThrow(id));
    }

    public PlanningDayDetail createPlanningDay(PlanningDayCreateRequest req) {
        UUID operatingClubId = resolveTenantOrThrow();
        // validatePlanningDate() runs at construction → InvalidPlanningDateException (422).
        PlanningDay day = PlanningDay.create(
                operatingClubId, req.planningDate(), req.locationId().value(), req.info());
        day.recordCreatedBy(currentUserId());
        applyCrew(day, req.instructorPersonId(), req.towingPilotPersonId(),
                req.flightOperatorPersonId());
        // Dedup-aware save → PlanningDayConflictException (409) on duplicate.
        PlanningDayDetail created = toDetail(planningDays.save(day));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id(), created));
        return created;
    }

    public PlanningDayDetail updatePlanningDay(UUID id, PlanningDayUpdateRequest req) {
        PlanningDay day = loadOrThrow(id);
        requireCanMutate(day);
        PlanningDayDetail before = toDetail(day);

        // reschedule re-runs validatePlanningDate() → InvalidPlanningDateException (422).
        day.reschedule(req.planningDate());
        day.reassignLocation(req.locationId().value());
        day.updateInfo(req.info());
        applyCrew(day, req.instructorPersonId(), req.towingPilotPersonId(),
                req.flightOperatorPersonId());

        PlanningDayDetail after = toDetail(planningDays.save(day));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id, before, after));
        return after;
    }

    public void deletePlanningDay(UUID id) {
        PlanningDay day = loadOrThrow(id);
        requireCanMutate(day);
        PlanningDayDetail before = toDetail(day);
        day.softDelete(currentUserId(), clock);
        planningDays.save(day);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
    }

    /**
     * One SPA page of the tenant's future planning days (legacy {@code POST
     * .../page/{start}/{size}}). The {@code Day.From} filter (default today)
     * windows to days at or after it; rows are sorted {@code planning_date asc}
     * (legacy default — the {@code sorting} map is honoured for completeness but
     * a descending future list is not a load-bearing J-6 view). {@code totalRows}
     * is the unpaged count of the same predicate.
     */
    @Transactional(readOnly = true)
    public PlanningDayPage page(int pageStart, int pageSize, @Nullable PlanningDayPageRequest request) {
        int safeStart = Math.max(pageStart, 0);
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LocalDate from = effectiveFrom(filterFrom(request));
        PlanningDayRepository.Page page = planningDays.findFuturePage(from, safeStart, safeSize);
        List<PlanningDayDetail> items = page.items().stream().map(this::toDetail).toList();
        return new PlanningDayPage(items, page.pageStart(), page.pageSize(), page.totalRows());
    }

    /** Future planning days ({@code planning_date >= today}) — the {@code overview/future} read. */
    @Transactional(readOnly = true)
    public List<PlanningDayDetail> overviewFuture() {
        return planningDays.findFutureListRows(today()).stream().map(this::toDetail).toList();
    }

    // ----- crew mapping -----

    private void applyCrew(PlanningDay day,
                           @Nullable PersonId instructor,
                           @Nullable PersonId towingPilot,
                           @Nullable PersonId flightOperator) {
        Map<PlanningRole, UUID> typeIds = roleTypeIds();
        assignRole(day, typeIds, PlanningRole.INSTRUCTOR, instructor);
        assignRole(day, typeIds, PlanningRole.TOWING_PILOT, towingPilot);
        assignRole(day, typeIds, PlanningRole.FLIGHT_OPERATOR, flightOperator);
    }

    private static void assignRole(PlanningDay day, Map<PlanningRole, UUID> typeIds,
                                   PlanningRole role, @Nullable PersonId person) {
        UUID typeId = typeIds.get(role);
        if (typeId == null) {
            // The club has no seeded type for this role: a null person is a no-op
            // (nothing to clear), but assigning one would have nowhere to land.
            if (person != null) {
                throw new IllegalArgumentException(
                        "Club has no planning assignment type for role " + role
                                + " (expected '" + role.typeName() + "')");
            }
            return;
        }
        day.assignRole(role, typeId, person == null ? null : person.value());
    }

    /** The caller-club's well-known {@link PlanningRole} → assignment-type-id map. */
    private Map<PlanningRole, UUID> roleTypeIds() {
        Map<PlanningRole, UUID> map = new EnumMap<>(PlanningRole.class);
        for (PlanningDayAssignmentType type : assignmentTypes.findActiveTypes()) {
            PlanningRole role = type.resolveRole();
            UUID id = type.getId();
            if (role != null && id != null) {
                map.putIfAbsent(role, id);
            }
        }
        return map;
    }

    // ----- detail mapping -----

    private PlanningDayDetail toDetail(PlanningDay day) {
        Map<UUID, PlanningRole> typeRoles = typeIdRoles();
        Map<PlanningRole, UUID> crew = new EnumMap<>(PlanningRole.class);
        day.getAssignments().forEach(a -> {
            PlanningRole role = typeRoles.get(a.getAssignmentTypeId());
            if (role != null) {
                crew.putIfAbsent(role, a.getAssignedPersonId());
            }
        });
        UUID id = Objects.requireNonNull(day.getId(), "Cannot map an unpersisted PlanningDay");
        long reservationCount = planningDays.countReservationsForDay(
                requirePlanningDate(day), requireLocationId(day));
        return PlanningDayMapper.toDetail(day, id, crew, reservationCount, canMutate(day));
    }

    /**
     * The list-row overload — used by the page / overview reads. Re-reads the
     * aggregate is avoided: the {@code ListRow} carries the day's own fields, and
     * crew + reservation count are resolved per row. Crew resolution needs the
     * aggregate's assignment rows, so the list reads load the day via {@code
     * findActiveById} (tenant-scoped) to populate the three crew slots — the
     * legacy overview DTO carries them too (J-6 oracle).
     */
    private PlanningDayDetail toDetail(ListRow row) {
        PlanningDay day = planningDays.findActiveById(row.id())
                .orElseThrow(() -> new PlanningDayNotFoundException(row.id()));
        return toDetail(day);
    }

    /** The caller-club's assignment-type-id → well-known {@link PlanningRole} map. */
    private Map<UUID, PlanningRole> typeIdRoles() {
        Map<UUID, PlanningRole> map = new java.util.HashMap<>();
        for (PlanningDayAssignmentType type : assignmentTypes.findActiveTypes()) {
            PlanningRole role = type.resolveRole();
            UUID id = type.getId();
            if (role != null && id != null) {
                map.put(id, role);
            }
        }
        return map;
    }

    // ----- helpers -----

    private static @Nullable LocalDate filterFrom(@Nullable PlanningDayPageRequest request) {
        if (request == null || request.searchFilter() == null
                || request.searchFilter().from() == null) {
            return null;
        }
        return request.searchFilter().from();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private LocalDate effectiveFrom(@Nullable LocalDate from) {
        return from == null ? today() : from;
    }

    private static LocalDate requirePlanningDate(PlanningDay day) {
        return Objects.requireNonNull(day.getPlanningDate(), "PlanningDay is missing planningDate");
    }

    private static UUID requireLocationId(PlanningDay day) {
        return Objects.requireNonNull(day.getLocationId(), "PlanningDay is missing locationId");
    }

    // ----- permission gate (ClubAdmin OR record creator; legacy :407-425) -----

    /**
     * Whether the current caller may update/delete {@code day}: a
     * {@code CLUB_ADMINISTRATOR} (or {@code SYSTEM_ADMINISTRATOR}) of the tenant,
     * OR the record's creator. Drives both the {@code canUpdate/canDeleteRecord}
     * DTO flags and the {@link #requireCanMutate} enforcement. Fail-closed: an
     * unauthenticated / unresolvable caller cannot mutate.
     */
    boolean canMutate(PlanningDay day) {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return false;
        }
        if (hasAnyRole(jwt, ROLE_SYSTEM_ADMIN, ROLE_CLUB_ADMIN)) {
            return true;
        }
        UUID creator = day.getCreatedByUserId();
        UUID caller = currentUserId();
        return creator != null && creator.equals(caller);
    }

    /** Enforces {@link #canMutate}; a non-admin non-creator gets a 403. */
    private void requireCanMutate(PlanningDay day) {
        if (!canMutate(day)) {
            throw new AccessDeniedException(
                    "Only a club administrator or the record's creator may modify this planning day");
        }
    }

    /** The caller's internal {@code user.id} (JWT sub → {@code t_user.id}), or null. */
    private @Nullable UUID currentUserId() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : principals.resolveUserIdFor(jwt).orElse(null);
    }

    private static @Nullable Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return jwtAuth.getToken();
        }
        return null;
    }

    private static boolean hasAnyRole(Jwt jwt, String... roles) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> ra)) {
            return false;
        }
        if (!(ra.get("roles") instanceof Collection<?> coll)) {
            return false;
        }
        for (Object role : coll) {
            if (role instanceof String name) {
                String prefixed = "ROLE_" + name;
                for (String wanted : roles) {
                    if (wanted.equals(prefixed)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private UUID resolveTenantOrThrow() {
        UUID tenant = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
            throw new IllegalStateException(
                    "PlanningDay.create requires a tenant context; unscoped caller cannot plan");
        }
        return tenant;
    }

    private PlanningDay loadOrThrow(UUID id) {
        return planningDays.findActiveById(id)
                .orElseThrow(() -> new PlanningDayNotFoundException(id));
    }
}
