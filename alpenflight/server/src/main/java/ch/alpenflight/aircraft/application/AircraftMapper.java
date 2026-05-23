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
                row.currentStateFlyable());
    }

    static AircraftPickerItem toPickerItem(AircraftRepository.PickerRow row) {
        return new AircraftPickerItem(
                AircraftId.of(row.id()),
                row.immatriculation(),
                AircraftTypeId.of(row.aircraftTypeId()),
                row.towingAircraft());
    }

    /**
     * Build a detail projection for the given principal. {@code ownerVisible}
     * == false drops fields the Security plan flags as owner-only / PII:
     * {@code comment} (FADP PII), {@code flarmId} + {@code mtom} +
     * {@code noiseClass} + {@code noiseLevel} + {@code spotLink}
     * (sensitive-not-PII). {@code immatriculation} stays — it's a regulator-
     * public identifier and appears in the list projection anyway.
     */
    static AircraftDetail toDetail(Aircraft a, boolean ownerVisible) {
        return new AircraftDetail(
                Objects.requireNonNull(a.getId(), "Cannot map an unpersisted Aircraft"),
                ClubId.ofNullable(a.getOwnerClubId()),
                Objects.requireNonNull(AircraftTypeId.ofNullable(a.getAircraftTypeId()),
                        "Aircraft is missing aircraftTypeId (NOT NULL in V3)"),
                a.getImmatriculation(),
                a.getManufacturerName(),
                a.getAircraftModel(),
                a.getCompetitionSign(),
                ownerVisible ? a.getFlarmId() : null,
                a.getAircraftSerialNumber(),
                a.getYearOfManufacture(),
                ownerVisible ? a.getNoiseClass() : null,
                ownerVisible ? a.getNoiseLevel() : null,
                ownerVisible ? a.getMtom() : null,
                a.getNrOfSeats(),
                a.getAircraftOwnerPersonId(),
                a.getFlightOperatingCounterUnitTypeId(),
                a.getEngineOperatingCounterUnitTypeId(),
                LocationId.ofNullable(a.getHomebaseId()),
                ownerVisible ? a.getSpotLink() : null,
                a.isTowingOrWinchRequired(),
                a.isTowingStartAllowed(),
                a.isWinchStartAllowed(),
                a.isTowingAircraft(),
                a.isFastEntryRecord(),
                ownerVisible ? a.getComment() : null,
                a.getDaecIndex(),
                a.getCurrentStateEntry().map(AircraftMapper::toStateResponse).orElse(null),
                a.getLatestCounter().map(AircraftMapper::toCounterResponse).orElse(null));
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
