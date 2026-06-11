package ch.alpenflight.flights.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Decoration-lookup port for the flight-report read-model projection
 * (ADR 0027 §2). Resolves the denormalized display columns a
 * {@link FlightReportRow} carries — aircraft immatriculation, person display
 * name, flight-type name/code, location name, start-type code — from their
 * owning aggregates at projection time.
 *
 * <p>Implemented in {@code flights.infra} with plain JPA queries (JPA-first,
 * ADR 0027 §1): cross-tenant sacred-cow references (Person, Aircraft) carry no
 * {@code @TenantId} and resolve regardless of tenant; tenant-scoped
 * decorations (FlightType, Location) ride the structural {@code @TenantId}
 * filter of the projecting session — a cross-tenant Location decorates as
 * {@code null} name (the row still carries the location id, so a later
 * rebuild can recover the name).
 *
 * <p>Every method returns {@code null} for a {@code null} or unresolvable id —
 * the report renders missing decorations as blanks (oracle LEFT-JOIN parity).
 */
public interface FlightReportDecorations {

    /** Aircraft immatriculation (cross-tenant read by PK). */
    @Nullable String immatriculation(@Nullable UUID aircraftId);

    /** Person display name as {@code "Lastname Firstname"} (oracle parity). */
    @Nullable String personName(@Nullable UUID personId);

    /** Flight-type name + code (tenant-scoped read). */
    @Nullable FlightTypeDecoration flightType(@Nullable UUID flightTypeId);

    /** Location display name (tenant-scoped read). */
    @Nullable String locationName(@Nullable UUID locationId);

    /** {@code t_start_type.code} (reference data, tenant-free). */
    @Nullable String startTypeCode(@Nullable UUID startTypeId);

    /** Pair of {@code t_flight_type.flight_code} / {@code flight_type_name}. */
    record FlightTypeDecoration(@Nullable String flightCode, String flightTypeName) {}
}
