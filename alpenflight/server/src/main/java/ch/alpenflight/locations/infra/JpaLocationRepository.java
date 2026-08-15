package ch.alpenflight.locations.infra;

import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaLocationRepository extends JpaRepository<Location, UUID>, LocationRepository {

    @Override
    @Query("select new ch.alpenflight.locations.domain.LocationRepository$ListRow("
            + "l.id, l.locationName, l.locationShortName, l.icaoCode, "
            + "t.code, t.isAirfield, l.fastEntryRecord) "
            + "from Location l, ch.alpenflight.referencedata.domain.LocationType t "
            + "where l.locationTypeId = t.id and l.deletedOn is null "
            + "order by case when l.sortIndicator is null then 1 else 0 end, "
            + "l.sortIndicator asc, l.locationName asc")
    List<LocationRepository.ListRow> findAllActiveListRows();

    @Override
    @EntityGraph(attributePaths = "inOutboundPoints")
    @Query("select l from Location l where l.id = :id and l.deletedOn is null")
    Optional<Location> findActiveByIdWithPoints(UUID id);

    @Override
    @Query("select l from Location l where l.id = :id and l.deletedOn is null")
    Optional<Location> findActiveById(UUID id);

    @Override
    @Query("select l from Location l where upper(l.icaoCode) = upper(:icaoCode) "
            + "and l.deletedOn is null")
    Optional<Location> findActiveByIcaoCodeIgnoreCase(String icaoCode);
}
