package ch.alpenflight.locations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface LocationRepository {

    record ListRow(UUID id,
                   String locationName,
                   @Nullable String locationShortName,
                   @Nullable String icaoCode,
                   String locationTypeCode,
                   boolean isAirfield,
                   boolean isFastEntryRecord) {}

    List<ListRow> findAllActiveListRows();

    Optional<Location> findActiveByIdWithPoints(UUID id);

    Optional<Location> findActiveById(UUID id);

    Optional<Location> findActiveByIcaoCodeIgnoreCase(String icaoCode);

    Location save(Location location);
}
