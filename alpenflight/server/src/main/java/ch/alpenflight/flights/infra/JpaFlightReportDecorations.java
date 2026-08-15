package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightReportDecorations;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

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
