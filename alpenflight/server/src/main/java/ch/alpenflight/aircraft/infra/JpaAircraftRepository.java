package ch.alpenflight.aircraft.infra;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of the {@link AircraftRepository} domain
 * port. Extends both the abstract port and {@code JpaRepository<Aircraft, UUID>}
 * so the application layer depends on the port (ADR 0023) while Spring
 * Data generates the runtime bean.
 *
 * <p>Soft-delete (V3 {@code deleted_on}) is filtered at the query layer.
 * Aircraft is cross-tenant (S-058 reversion of S-159) — queries return
 * rows from any club; the catalog is intentionally shared.
 *
 * <p>List + picker rows are flat projection DTOs to avoid N+1 across
 * {@code t_aircraft_type} + current {@code t_aircraft_aircraft_state}. The
 * partial-unique {@code ux_aas_current_state_per_aircraft} index lets the
 * "current state" LEFT JOIN serve as an Index Only Scan.
 */
public interface JpaAircraftRepository extends JpaRepository<Aircraft, UUID>, AircraftRepository {

    @Override
    @Query("select new ch.alpenflight.aircraft.domain.AircraftRepository$ListRow("
            + "a.id, a.ownerClubId, a.immatriculation, a.competitionSign, "
            + "a.aircraftTypeId, t.code, t.hasEngine, a.towingAircraft, "
            + "s.code, s.isAircraftFlyable, "
            + "a.manufacturerName, a.aircraftModel, a.nrOfSeats) "
            + "from Aircraft a "
            + "join ch.alpenflight.referencedata.domain.AircraftType t on a.aircraftTypeId = t.id "
            + "left join ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry h "
            + "  on h.aircraft = a and h.validTo is null and h.deletedOn is null "
            + "left join ch.alpenflight.referencedata.domain.AircraftState s "
            + "  on h.aircraftStateId = s.id "
            + "where a.deletedOn is null "
            + "order by a.immatriculation asc")
    List<AircraftRepository.ListRow> findAllActiveListRows();

    @Override
    @Query("select new ch.alpenflight.aircraft.domain.AircraftRepository$ListRow("
            + "a.id, a.ownerClubId, a.immatriculation, a.competitionSign, "
            + "a.aircraftTypeId, t.code, t.hasEngine, a.towingAircraft, "
            + "s.code, s.isAircraftFlyable, "
            + "a.manufacturerName, a.aircraftModel, a.nrOfSeats) "
            + "from Aircraft a "
            + "join ch.alpenflight.referencedata.domain.AircraftType t on a.aircraftTypeId = t.id "
            + "left join ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry h "
            + "  on h.aircraft = a and h.validTo is null and h.deletedOn is null "
            + "left join ch.alpenflight.referencedata.domain.AircraftState s "
            + "  on h.aircraftStateId = s.id "
            + "where a.deletedOn is null and t.code in :codes "
            + "order by a.immatriculation asc")
    List<AircraftRepository.ListRow> findActiveListRowsByTypeCodeIn(@Param("codes") Set<String> typeCodes);

    @Override
    @Query("select new ch.alpenflight.aircraft.domain.AircraftRepository$ListRow("
            + "a.id, a.ownerClubId, a.immatriculation, a.competitionSign, "
            + "a.aircraftTypeId, t.code, t.hasEngine, a.towingAircraft, "
            + "s.code, s.isAircraftFlyable, "
            + "a.manufacturerName, a.aircraftModel, a.nrOfSeats) "
            + "from Aircraft a "
            + "join ch.alpenflight.referencedata.domain.AircraftType t on a.aircraftTypeId = t.id "
            + "left join ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry h "
            + "  on h.aircraft = a and h.validTo is null and h.deletedOn is null "
            + "left join ch.alpenflight.referencedata.domain.AircraftState s "
            + "  on h.aircraftStateId = s.id "
            + "where a.deletedOn is null and a.towingAircraft = true "
            + "order by a.immatriculation asc")
    List<AircraftRepository.ListRow> findActiveTowingListRows();

    @Override
    @Query("select new ch.alpenflight.aircraft.domain.AircraftRepository$PickerRow("
            + "a.id, a.immatriculation, a.aircraftTypeId, a.towingAircraft, a.nrOfSeats) "
            + "from Aircraft a "
            + "where a.deletedOn is null "
            + "order by a.immatriculation asc")
    List<AircraftRepository.PickerRow> findAllActivePickerRows();

    @Override
    @Query("select a from Aircraft a where a.id = :id and a.deletedOn is null")
    Optional<Aircraft> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select a from Aircraft a where upper(a.immatriculation) = upper(:imm) "
            + "and a.deletedOn is null")
    Optional<Aircraft> findActiveByImmatriculation(@Param("imm") String normalizedImmatriculation);
}
