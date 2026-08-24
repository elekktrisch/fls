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

    String LIST_ROW_PROJECTION =
            "select new ch.alpenflight.aircraft.domain.AircraftRepository$ListRow("
                    + "a.id, a.ownerClubId, a.immatriculation, a.competitionSign, "
                    + "a.aircraftTypeId, t.code, t.hasEngine, a.towingAircraft, "
                    + "s.code, s.isAircraftFlyable, "
                    + "a.manufacturerName, a.aircraftModel, a.nrOfSeats) ";

    String PICKER_ROW_PROJECTION =
            "select new ch.alpenflight.aircraft.domain.AircraftRepository$PickerRow("
                    + "a.id, a.immatriculation, a.aircraftTypeId, a.towingAircraft, a.nrOfSeats) ";

    String FROM_AIRCRAFT_MANAGED_INSIDE_THE_READING_CLUBS_DEPLOYMENT =
            "from Aircraft a "
                    + "join ch.alpenflight.clubs.domain.Club managing "
                    + "  on managing.id = a.managingClubId "
                    + "join ch.alpenflight.clubs.domain.Club reading "
                    + "  on reading.id = :readingClubId "
                    + "  and reading.deploymentId = managing.deploymentId ";

    String JOINED_TO_TYPE_AND_CURRENT_STATE =
            "join ch.alpenflight.referencedata.domain.AircraftType t on a.aircraftTypeId = t.id "
                    + "left join ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry h "
                    + "  on h.aircraft = a and h.validTo is null and h.deletedOn is null "
                    + "left join ch.alpenflight.referencedata.domain.AircraftState s "
                    + "  on h.aircraftStateId = s.id ";

    String WHERE_NOT_SOFT_DELETED = "where a.deletedOn is null ";

    String ORDERED_BY_IMMATRICULATION = "order by a.immatriculation asc";

    @Override
    @Query(LIST_ROW_PROJECTION
            + FROM_AIRCRAFT_MANAGED_INSIDE_THE_READING_CLUBS_DEPLOYMENT
            + JOINED_TO_TYPE_AND_CURRENT_STATE
            + WHERE_NOT_SOFT_DELETED
            + ORDERED_BY_IMMATRICULATION)
    List<AircraftRepository.ListRow> findActiveListRowsInSameDeploymentAs(
            @Param("readingClubId") UUID readingClubId);

    @Override
    @Query(LIST_ROW_PROJECTION
            + FROM_AIRCRAFT_MANAGED_INSIDE_THE_READING_CLUBS_DEPLOYMENT
            + JOINED_TO_TYPE_AND_CURRENT_STATE
            + WHERE_NOT_SOFT_DELETED
            + "and t.code in :codes "
            + ORDERED_BY_IMMATRICULATION)
    List<AircraftRepository.ListRow> findActiveListRowsByTypeCodeInSameDeploymentAs(
            @Param("codes") Set<String> typeCodes,
            @Param("readingClubId") UUID readingClubId);

    @Override
    @Query(LIST_ROW_PROJECTION
            + FROM_AIRCRAFT_MANAGED_INSIDE_THE_READING_CLUBS_DEPLOYMENT
            + JOINED_TO_TYPE_AND_CURRENT_STATE
            + WHERE_NOT_SOFT_DELETED
            + "and a.towingAircraft = true "
            + ORDERED_BY_IMMATRICULATION)
    List<AircraftRepository.ListRow> findActiveTowingListRowsInSameDeploymentAs(
            @Param("readingClubId") UUID readingClubId);

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
    @Query(PICKER_ROW_PROJECTION
            + FROM_AIRCRAFT_MANAGED_INSIDE_THE_READING_CLUBS_DEPLOYMENT
            + WHERE_NOT_SOFT_DELETED
            + ORDERED_BY_IMMATRICULATION)
    List<AircraftRepository.PickerRow> findActivePickerRowsInSameDeploymentAs(
            @Param("readingClubId") UUID readingClubId);

    String MANAGED_BY_A_CLUB_OF_A_DEPLOYMENT_THAT_IS_NOT_A_SANDBOX =
            "join ch.alpenflight.clubs.domain.Club managingOutsideSandbox "
                    + "  on managingOutsideSandbox.id = a.managingClubId "
                    + "join ch.alpenflight.deployments.domain.Deployment hostingDeployment "
                    + "  on hostingDeployment.id = managingOutsideSandbox.deploymentId "
                    + "  and hostingDeployment.lifecycleState <> "
                    + "      ch.alpenflight.deployments.domain.LifecycleState.SANDBOX ";

    @Override
    @Query("select a from Aircraft a "
            + MANAGED_BY_A_CLUB_OF_A_DEPLOYMENT_THAT_IS_NOT_A_SANDBOX
            + "where a.deletedOn is null order by a.immatriculation")
    List<Aircraft> findAllActiveOutsideEverySandboxDeployment();

    @Override
    @Query("select a from Aircraft a where a.id = :id and a.deletedOn is null")
    Optional<Aircraft> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select a "
            + FROM_AIRCRAFT_MANAGED_INSIDE_THE_READING_CLUBS_DEPLOYMENT
            + WHERE_NOT_SOFT_DELETED
            + "and a.id = :id")
    Optional<Aircraft> findActiveByIdInSameDeploymentAs(
            @Param("id") UUID id,
            @Param("readingClubId") UUID readingClubId);

    @Override
    @Query("select a from Aircraft a where upper(a.immatriculation) = upper(:imm) "
            + "and a.deletedOn is null")
    Optional<Aircraft> findActiveByImmatriculation(@Param("imm") String normalizedImmatriculation);
}
