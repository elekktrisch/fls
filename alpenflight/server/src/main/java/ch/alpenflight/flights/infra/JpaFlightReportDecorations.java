package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightReportDecorations;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * JPA implementation of {@link FlightReportDecorations} (ADR 0027 §1 —
 * JPA-first; replaces what would otherwise be native decoration joins).
 * Queries are JPQL by ENTITY NAME ({@code Aircraft}, {@code Person},
 * {@code FlightType}, {@code Location}) — the projection reads single scalar
 * columns of other modules' aggregates without importing their classes, so
 * the flights module's compile-time surface stays inside its own boundary
 * (Spring Modulith) while the reads still run through Hibernate:
 *
 * <ul>
 *   <li>{@code Person} / {@code Aircraft} carry no {@code @TenantId}
 *       (sacred-cow cross-tenant ride-throughs, S-051 / S-058) — plain reads
 *       resolve regardless of tenant, matching the oracle's decoration
 *       joins;</li>
 *   <li>{@code FlightType} / {@code Location} are tenant-scoped — the
 *       {@code @TenantId} filter of the projecting session applies
 *       structurally. A cross-club Location decorates as null name; the row
 *       keeps the location ID so a rebuild can recover it (RM-2).</li>
 *   <li>{@code t_start_type} is tenant-free reference data, read via the
 *       module-local {@link StartTypeProjection}.</li>
 * </ul>
 */
@Component
class JpaFlightReportDecorations implements FlightReportDecorations {

    private final EntityManager em;

    JpaFlightReportDecorations(EntityManager em) {
        this.em = em;
    }

    @Override
    public @Nullable String immatriculation(@Nullable UUID aircraftId) {
        if (aircraftId == null) {
            return null;
        }
        return scalar("select a.immatriculation from Aircraft a where a.id = :id", aircraftId);
    }

    @Override
    public @Nullable String personName(@Nullable UUID personId) {
        if (personId == null) {
            return null;
        }
        // "Lastname Firstname" — oracle parity (t_person lastname || ' ' || firstname).
        Object[] name = row("select p.lastname, p.firstname from Person p where p.id = :id",
                personId);
        if (name == null) {
            return null;
        }
        return name[0] + " " + name[1];
    }

    @Override
    public @Nullable FlightTypeDecoration flightType(@Nullable UUID flightTypeId) {
        if (flightTypeId == null) {
            return null;
        }
        Object[] type = row("select ft.flightCode, ft.flightTypeName from FlightType ft"
                + " where ft.id = :id", flightTypeId);
        if (type == null) {
            return null;
        }
        return new FlightTypeDecoration((String) type[0], (String) type[1]);
    }

    @Override
    public @Nullable String locationName(@Nullable UUID locationId) {
        if (locationId == null) {
            return null;
        }
        return scalar("select l.locationName from Location l where l.id = :id", locationId);
    }

    @Override
    public @Nullable String startTypeCode(@Nullable UUID startTypeId) {
        if (startTypeId == null) {
            return null;
        }
        return scalar("select s.code from StartTypeProjection s where s.id = :id", startTypeId);
    }

    private @Nullable String scalar(String jpql, UUID id) {
        return em.createQuery(jpql, String.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private Object @Nullable [] row(String jpql, UUID id) {
        return em.createQuery(jpql, Object[].class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }
}
