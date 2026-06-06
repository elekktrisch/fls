package ch.alpenflight.planning.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link PlanningDay} persistence (J-6 T-03). Implemented by
 * {@code ch.alpenflight.planning.infra.JpaPlanningDayRepository} (Spring Data).
 *
 * <p>Planning days are tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code operating_club_id} (ADR 0008); every read + write
 * carries the tenant predicate automatically, so cross-tenant ids are
 * invisible (cross-tenant GET → 404). Soft-delete ({@code deleted_on}) is
 * filtered at the query layer.
 *
 * <p>The list reads mirror J-5's reservation read-side: a paged future-days
 * envelope ({@link Page}) and an unpaged overview/future list, both projecting
 * the flat FK ids the SPA decorates (ADR 0023 — no cross-module label joins).
 * The per-day {@code NumberOfAircraftReservations} is a <em>computed</em> count
 * over {@code t_aircraft_reservation} (same club, {@code date(reservation_start)
 * == planning_date}, same location) — never stored (J-6 oracle).
 */
public interface PlanningDayRepository {

    /**
     * Flat projection row for the {@code /api/v1/planningdays} list + paged
     * future-days view. Carries the FK ids the SPA needs to render a row
     * (location, the 3 well-known crew roles) in a single round-trip; the web
     * layer decorates display labels from picker payloads (ADR 0023). Crew ids
     * resolve from the generic assignment rows by well-known role
     * ({@link PlanningRole}) at the service layer (T-04), so this row stays a
     * pure {@link PlanningDay} projection.
     */
    record ListRow(UUID id,
                   LocalDate planningDate,
                   UUID locationId,
                   @Nullable String info) {}

    /**
     * Paged-list envelope mirroring J-5's reservation paged shape
     * ({@code {Items, PageStart, PageSize, TotalRows}}). {@code totalRows} is
     * the unpaged count of the same predicate so the SPA can render the pager.
     */
    record Page(List<ListRow> items, int pageStart, int pageSize, long totalRows) {}

    PlanningDay save(PlanningDay planningDay);

    Optional<PlanningDay> findActiveById(UUID id);

    /**
     * Whether the caller's tenant already has a non-deleted planning day on
     * {@code planningDate} at {@code locationId} — the {@code (club, date,
     * location)} dedup key the {@code ux_pln_club_date_loc} index enforces. Used
     * by the bulk rule-expand (T-05) to skip an already-created day idempotently
     * (re-running the same rule is a no-op for existing days) rather than letting
     * the unique-index breach surface as a 409. Tenant-scoped via {@code @TenantId};
     * a cross-tenant day at the same date/location is invisible (so each club's
     * rule is independent).
     */
    boolean existsActiveForDay(LocalDate planningDate, UUID locationId);

    /**
     * One page of future planning days ({@code planning_date >= asOf}) within
     * the caller's tenant, sorted {@code planning_date asc}, windowed to
     * {@code [pageStart, pageStart+pageSize)}. Backs {@code POST
     * .../page/{start}/{size}}. {@code pageSize} defaults to 100 at the
     * controller (legacy default); a non-positive size yields an empty page.
     */
    Page findFuturePage(LocalDate asOf, int pageStart, int pageSize);

    /**
     * All future planning days ({@code planning_date >= asOf}) within the
     * caller's tenant, sorted {@code planning_date asc} — the {@code
     * overview/future} read.
     */
    List<ListRow> findFutureListRows(LocalDate asOf);

    /**
     * The legacy {@code NumberOfAircraftReservations}: count of non-deleted
     * aircraft reservations for the caller's tenant whose
     * {@code date(reservation_start)} equals {@code planningDate} at
     * {@code locationId}. Computed on read, never stored (J-6 oracle).
     */
    long countReservationsForDay(LocalDate planningDate, UUID locationId);

    /** Flushes the persistence context (mirrors the reservations port convention). */
    void flush();
}
