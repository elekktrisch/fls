package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCounterHistory;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftListItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftOperatingCounterResponse;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftPickerItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateHistory;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateHistoryEntryResponse;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftOperatingCounter;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.AircraftStateId;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.LocationId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class AircraftMapper {

    private AircraftMapper() {}

    static AircraftListItem toListItem(AircraftRepository.ListRow row) {
        return new AircraftListItem(
                AircraftId.of(row.id()),
                ClubId.ofNullable(row.ownerClubId()),
                row.immatriculation(),
                row.competitionSign(),
                AircraftTypeId.of(row.aircraftTypeId()),
                row.aircraftTypeCode(),
                Boolean.TRUE.equals(row.aircraftTypeHasEngine()),
                row.towingAircraft(),
                row.currentStateCode(),
                row.currentStateFlyable(),
                row.manufacturerName(),
                row.aircraftModel(),
                row.nrOfSeats());
    }

    static AircraftPickerItem toPickerItem(AircraftRepository.PickerRow row) {
        return new AircraftPickerItem(
                AircraftId.of(row.id()),
                row.immatriculation(),
                AircraftTypeId.of(row.aircraftTypeId()),
                row.towingAircraft(),
                row.nrOfSeats());
    }

    /**
     * Build the detail projection with the manager-only counter inlined.
     * Used on write paths (register / update / transfer), where the caller
     * is the managing club by construction. Read paths use the caller-aware
     * {@link #toDetail(Aircraft, boolean)} overload.
     */
    static AircraftDetail toDetail(Aircraft a) {
        return toDetail(a, true);
    }

    /**
     * Build the detail projection, redacting the manager-only
     * {@code latestCounter} when {@code includeLatestCounter} is false
     * (S-164). Aircraft is cross-tenant (S-058 reversion of S-159) — any
     * authenticated user may read the row to surface it on a Flight picker —
     * but the managing club's operational counter is nulled for non-managing
     * readers via the {@code AircraftAccess.canViewManagerOnlyData} predicate
     * (same managing-club match as edit). The counter-history list endpoint
     * gates to the managing club via {@code AircraftAccess} the same way.
     * Other fields (flarm id, mtom, comment) are intentionally NOT redacted
     * here — only {@code latestCounter} per the S-164 AC.
     */
    static AircraftDetail toDetail(Aircraft a, boolean includeLatestCounter) {
        return new AircraftDetail(
                Objects.requireNonNull(a.getId(), "Cannot map an unpersisted Aircraft"),
                ClubId.ofNullable(a.getOwnerClubId()),
                Objects.requireNonNull(AircraftTypeId.ofNullable(a.getAircraftTypeId()),
                        "Aircraft is missing aircraftTypeId (NOT NULL in V3)"),
                a.getImmatriculation(),
                a.getManufacturerName(),
                a.getAircraftModel(),
                a.getCompetitionSign(),
                a.getFlarmId(),
                a.getAircraftSerialNumber(),
                a.getYearOfManufacture(),
                a.getNoiseClass(),
                a.getNoiseLevel(),
                a.getMtom(),
                a.getNrOfSeats(),
                a.getAircraftOwnerPersonId(),
                a.getFlightOperatingCounterUnitTypeId(),
                a.getEngineOperatingCounterUnitTypeId(),
                LocationId.ofNullable(a.getHomebaseId()),
                a.getSpotLink(),
                a.isTowingOrWinchRequired(),
                a.isTowingStartAllowed(),
                a.isWinchStartAllowed(),
                a.isTowingAircraft(),
                a.isFastEntryRecord(),
                a.getComment(),
                a.getDaecIndex(),
                a.getCurrentStateEntry().map(AircraftMapper::toStateResponse).orElse(null),
                includeLatestCounter
                        ? a.getLatestCounter().map(AircraftMapper::toCounterResponse).orElse(null)
                        : null);
    }

    static AircraftStateHistoryEntryResponse toStateResponse(AircraftStateHistoryEntry entry) {
        return new AircraftStateHistoryEntryResponse(
                Objects.requireNonNull(entry.getId(), "Cannot map an unpersisted state-history entry"),
                Objects.requireNonNull(AircraftStateId.ofNullable(entry.getAircraftStateId()),
                        "state entry is missing aircraftStateId"),
                Objects.requireNonNull(entry.getValidFrom(), "state entry is missing validFrom"),
                entry.getValidTo(),
                entry.getNoticedByPersonId(),
                entry.getRemarks());
    }

    static AircraftOperatingCounterResponse toCounterResponse(AircraftOperatingCounter counter) {
        return new AircraftOperatingCounterResponse(
                Objects.requireNonNull(counter.getId(), "Cannot map an unpersisted counter"),
                Objects.requireNonNull(counter.getAtDateTime(), "counter is missing atDateTime"),
                counter.getTotalTowedGliderStarts(),
                counter.getTotalWinchLaunchStarts(),
                counter.getTotalSelfStarts(),
                counter.getFlightOperatingCounterInSeconds(),
                counter.getEngineOperatingCounterInSeconds(),
                counter.getNextMaintenanceAtFlightOperatingCounterInSeconds(),
                counter.getNextMaintenanceAtEngineOperatingCounterInSeconds());
    }

    static AircraftStateHistory toStateHistory(Aircraft a) {
        List<AircraftStateHistoryEntryResponse> entries = a.getStateHistory().stream()
                .sorted(Comparator.comparing(
                        (AircraftStateHistoryEntry e) -> reqValidFrom(e)).reversed())
                .map(AircraftMapper::toStateResponse)
                .toList();
        return new AircraftStateHistory(entries);
    }

    static AircraftCounterHistory toCounterHistory(Aircraft a) {
        List<AircraftOperatingCounterResponse> entries = a.getOperatingCounters().stream()
                .sorted(Comparator.comparing(
                        (AircraftOperatingCounter c) -> reqAtDateTime(c)).reversed())
                .map(AircraftMapper::toCounterResponse)
                .toList();
        return new AircraftCounterHistory(entries);
    }

    @SuppressWarnings("NullAway")
    private static java.time.Instant reqValidFrom(AircraftStateHistoryEntry e) {
        java.time.Instant v = e.getValidFrom();
        if (v == null) {
            throw new IllegalStateException("Persisted state entry has null validFrom");
        }
        return v;
    }

    @SuppressWarnings("NullAway")
    private static java.time.Instant reqAtDateTime(AircraftOperatingCounter c) {
        java.time.Instant v = c.getAtDateTime();
        if (v == null) {
            throw new IllegalStateException("Persisted counter has null atDateTime");
        }
        return v;
    }
}
