package ch.alpenflight.planning.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayCreateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayDetail;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPage;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPageRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayRuleRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayUpdateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayValidateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayValidationResult;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.planning.domain.PlanningDayAssignmentTypeRepository;
import ch.alpenflight.planning.domain.PlanningDayNotFoundException;
import ch.alpenflight.planning.domain.PlanningDayRepository;
import ch.alpenflight.planning.domain.PlanningDayRepository.ListRow;
import ch.alpenflight.planning.domain.PlanningRole;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.security.CurrentPrincipal;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlanningDaysService {

    private static final String AUDIT_ENTITY_TYPE = "PlanningDay";

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private static final String ROLE_SYSTEM_ADMIN = "ROLE_SYSTEM_ADMINISTRATOR";
    private static final String ROLE_CLUB_ADMIN = "ROLE_CLUB_ADMINISTRATOR";

    private final PlanningDayRepository planningDays;
    private final PlanningDayAssignmentTypeRepository assignmentTypes;
    private final CurrentPrincipal currentPrincipal;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public PlanningDaysService(PlanningDayRepository planningDays,
                               PlanningDayAssignmentTypeRepository assignmentTypes,
                               CurrentPrincipal currentPrincipal,
                               ClubTenantIdentifierResolver tenantResolver,
                               Clock clock,
                               AuditTrail auditTrail) {
        this.planningDays = planningDays;
        this.assignmentTypes = assignmentTypes;
        this.currentPrincipal = currentPrincipal;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public PlanningDayDetail getPlanningDay(UUID id) {
        return toDetail(loadOrThrow(id));
    }

    @Transactional(readOnly = true)
    public PlanningDayValidationResult validateUniqueness(PlanningDayValidateRequest req) {
        boolean duplicate = planningDays.existsActiveForDayExcluding(
                req.planningDate(), req.locationId().value(), req.excludePlanningDayId());
        if (duplicate) {
            return PlanningDayValidationResult.failed(
                    "planningDate", "A planning day already exists for this date and location.");
        }
        return PlanningDayValidationResult.passed();
    }

    public PlanningDayDetail createPlanningDay(PlanningDayCreateRequest req) {
        UUID operatingClubId = resolveTenantOrThrow();
        PlanningDay day = PlanningDay.create(
                operatingClubId, req.planningDate(), req.locationId().value(), req.info());
        day.recordCreatedBy(currentPrincipal.userId().orElse(null));
        applyCrew(day, req.instructorPersonId(), req.towingPilotPersonId(),
                req.flightOperatorPersonId());
        PlanningDayDetail created = toDetail(planningDays.save(day));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id(), created));
        return created;
    }

    public List<PlanningDayDetail> bulkCreatePlanningDays(PlanningDayRuleRequest req) {
        UUID operatingClubId = resolveTenantOrThrow();
        UUID locationId = req.locationId().value();
        Set<DayOfWeek> weekdays = req.selectedWeekdays();
        List<LocalDate> dates = PlanningDay.expandRuleDates(req.startDate(), req.endDate(), weekdays);
        if (dates.isEmpty()) {
            return List.of();
        }
        UUID creator = currentPrincipal.userId().orElse(null);
        List<PlanningDayDetail> created = new ArrayList<>();
        for (LocalDate date : dates) {
            if (planningDays.existsActiveForDay(date, locationId)) {
                continue;
            }
            PlanningDay day = PlanningDay.create(operatingClubId, date, locationId, req.info());
            day.recordCreatedBy(creator);
            PlanningDayDetail detail = toDetail(planningDays.save(day));
            auditTrail.record(AuditAction.CREATE,
                    AuditedTarget.created(AUDIT_ENTITY_TYPE, detail.id(), detail));
            created.add(detail);
        }
        return List.copyOf(created);
    }

    public PlanningDayDetail updatePlanningDay(UUID id, PlanningDayUpdateRequest req) {
        PlanningDay day = loadOrThrow(id);
        requireCanMutate(day);
        PlanningDayDetail before = toDetail(day);

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
        day.softDelete(currentPrincipal.userId().orElse(null), clock);
        planningDays.save(day);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
    }

    @Transactional(readOnly = true)
    public PlanningDayPage page(int pageStart, int pageSize, @Nullable PlanningDayPageRequest request) {
        int safeStart = Math.max(pageStart, 0);
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LocalDate from = effectiveFrom(filterFrom(request));
        PlanningDayRepository.Page page = planningDays.findFuturePage(from, safeStart, safeSize);
        List<PlanningDayDetail> items = page.items().stream().map(this::toDetail).toList();
        return new PlanningDayPage(items, page.pageStart(), page.pageSize(), page.totalRows());
    }

    @Transactional(readOnly = true)
    public List<PlanningDayDetail> overviewFuture() {
        return planningDays.findFutureListRows(today()).stream().map(this::toDetail).toList();
    }


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
            if (person != null) {
                throw new IllegalArgumentException(
                        "Club has no planning assignment type for role " + role
                                + " (expected '" + role.typeName() + "')");
            }
            return;
        }
        day.assignRole(role, typeId, person == null ? null : person.value());
    }

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

    private PlanningDayDetail toDetail(ListRow row) {
        PlanningDay day = planningDays.findActiveById(row.id())
                .orElseThrow(() -> new PlanningDayNotFoundException(row.id()));
        return toDetail(day);
    }

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


    boolean canMutate(PlanningDay day) {
        Jwt jwt = currentPrincipal.jwt().orElse(null);
        if (jwt == null) {
            return false;
        }
        if (hasAnyRole(jwt, ROLE_SYSTEM_ADMIN, ROLE_CLUB_ADMIN)) {
            return true;
        }
        UUID creator = day.getCreatedByUserId();
        UUID caller = currentPrincipal.userId().orElse(null);
        return creator != null && creator.equals(caller);
    }

    private void requireCanMutate(PlanningDay day) {
        if (!canMutate(day)) {
            throw new AccessDeniedException(
                    "Only a club administrator or the record's creator may modify this planning day");
        }
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
