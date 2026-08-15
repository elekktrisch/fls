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
    @Query("select a.id from Aircraft a "
            + "join ch.alpenflight.referencedata.domain.AircraftType t on a.aircraftTypeId = t.id "
            + "where a.deletedOn is null and a.ownerClubId = :ownerClubId "
            + "  and t.code = :typeCode and a.nrOfSeats = :nrOfSeats "
            + "order by a.immatriculation asc")
    List<UUID> findActiveOwnedIdsByTypeCodeAndSeatsOrderedByImmatriculation(@Param("ownerClubId") UUID ownerClubId,
                                                    @Param("typeCode") String typeCode,
                                                    @Param("nrOfSeats") int nrOfSeats);

    @Override
    @Query("select new ch.alpenflight.aircraft.domain.AircraftRepository$PickerRow("
            + "a.id, a.immatriculation, a.aircraftTypeId, a.towingAircraft, a.nrOfSeats) "
            + "from Aircraft a "
            + "where a.deletedOn is null "
            + "order by a.immatriculation asc")
    List<AircraftRepository.PickerRow> findAllActivePickerRows();

    @Override
    @Query("select a from Aircraft a where a.deletedOn is null order by a.immatriculation")
    List<Aircraft> findAllActive();

    @Override
    @Query("select a from Aircraft a where a.id = :id and a.deletedOn is null")
    Optional<Aircraft> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select a from Aircraft a where upper(a.immatriculation) = upper(:imm) "
            + "and a.deletedOn is null")
    Optional<Aircraft> findActiveByImmatriculation(@Param("imm") String normalizedImmatriculation);
}
