package ch.alpenflight.aircraft.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface AircraftRepository {

    record ListRow(UUID id,
                   @Nullable UUID ownerClubId,
                   String immatriculation,
                   @Nullable String competitionSign,
                   UUID aircraftTypeId,
                   String aircraftTypeCode,
                   @Nullable Boolean aircraftTypeHasEngine,
                   boolean towingAircraft,
                   @Nullable String currentStateCode,
                   @Nullable Boolean currentStateFlyable,
                   @Nullable String manufacturerName,
                   @Nullable String aircraftModel,
                   @Nullable Integer nrOfSeats) {}

    record PickerRow(UUID id,
                     String immatriculation,
                     UUID aircraftTypeId,
                     boolean towingAircraft,
                     @Nullable Integer nrOfSeats) {}

    List<ListRow> findAllActiveListRows();

    List<ListRow> findActiveListRowsByTypeCodeIn(java.util.Set<String> typeCodes);

    List<ListRow> findActiveTowingListRows();

    // RENAME: findActiveOwnedIdsByTypeCodeAndSeats -> findActiveOwnedIdsByTypeCodeAndSeatsOrderedByImmatriculation
    List<UUID> findActiveOwnedIdsByTypeCodeAndSeats(UUID ownerClubId,
                                                    String typeCode,
                                                    int nrOfSeats);

    List<PickerRow> findAllActivePickerRows();

    Optional<Aircraft> findActiveById(UUID id);

    Optional<Aircraft> findActiveByImmatriculation(String normalizedImmatriculation);

    List<Aircraft> findAllActive();

    Aircraft save(Aircraft aircraft);

    void flush();
}
