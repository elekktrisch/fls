package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCounterHistory;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCounterRecordRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftListItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftOperatingCounterResponse;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftPickerItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateChangeRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateHistory;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateHistoryEntryResponse;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftTransferOwnershipRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftUpdateRequest;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftNotFoundException;
import ch.alpenflight.aircraft.domain.AircraftOperatingCounter;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.aircraft.domain.AircraftStateConflictException;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import ch.alpenflight.aircraft.domain.CounterMonotonicityException;
import ch.alpenflight.aircraft.domain.DuplicateImmatriculationException;
import ch.alpenflight.aircraft.domain.Immatriculation;
import ch.alpenflight.aircraft.domain.InvalidAircraftReferenceException;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.referencedata.domain.AircraftStateRepository;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@link Aircraft} aggregate. Authz is
 * enforced by {@code @PreAuthorize} on the controller via
 * {@link AircraftAccess}; this service trusts that the caller has been
 * gated.
 *
 * <p>Immatriculation uniqueness is GLOBAL (regulator-convention; partial
 * UNIQUE {@code ux_aircraft_immatriculation} WHERE {@code deleted_on IS
 * NULL}). The service does a UX pre-check + relies on the index for races.
 *
 * <p>TODO(S-027): emit {@code AIRCRAFT_REGISTERED} / {@code AIRCRAFT_UPDATED}
 * / {@code AIRCRAFT_DELETED} / {@code AIRCRAFT_STATE_CHANGED} /
 * {@code AIRCRAFT_COUNTER_RECORDED} / {@code AIRCRAFT_OWNERSHIP_TRANSFERRED}
 * audit events once the unified audit emitter ships.
 */
@Service
@Transactional
public class AircraftsService {

    private final AircraftRepository aircrafts;
    private final AircraftTypeRepository aircraftTypes;
    private final AircraftStateRepository aircraftStates;
    private final Clock clock;

    public AircraftsService(AircraftRepository aircrafts,
                            AircraftTypeRepository aircraftTypes,
                            AircraftStateRepository aircraftStates,
                            Clock clock) {
        this.aircrafts = aircrafts;
        this.aircraftTypes = aircraftTypes;
        this.aircraftStates = aircraftStates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AircraftListItem> listAircraft() {
        return aircrafts.findAllActiveListRows().stream()
                .map(AircraftMapper::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AircraftPickerItem> listAircraftForPicker() {
        return aircrafts.findAllActivePickerRows().stream()
                .map(AircraftMapper::toPickerItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public AircraftDetail getAircraft(AircraftId id) {
        return AircraftMapper.toDetail(loadOrThrow(id));
    }

    public AircraftDetail registerAircraft(AircraftCreateRequest req) {
        validateAircraftType(req.aircraftTypeId().value());
        String normalized = Immatriculation.of(req.immatriculation()).normalized();
        aircrafts.findActiveByImmatriculation(normalized)
                .ifPresent(existing -> {
                    throw new DuplicateImmatriculationException(normalized);
                });

        Aircraft a = Aircraft.register(
                req.ownerClubId() == null ? null : req.ownerClubId().value(),
                req.aircraftTypeId().value(),
                req.immatriculation(),
                req.manufacturerName(),
                req.aircraftModel(),
                req.competitionSign(),
                req.flarmId(),
                req.aircraftSerialNumber(),
                req.yearOfManufacture(),
                req.noiseClass(),
                req.noiseLevel(),
                req.mtom(),
                req.nrOfSeats(),
                req.aircraftOwnerPersonId(),
                req.flightOperatingCounterUnitTypeId(),
                req.engineOperatingCounterUnitTypeId(),
                req.homebaseId() == null ? null : req.homebaseId().value(),
                req.spotLink(),
                req.isTowingOrWinchRequired(),
                req.isTowingStartAllowed(),
                req.isWinchStartAllowed(),
                req.isTowingAircraft(),
                req.isFastEntryRecord(),
                req.comment(),
                req.daecIndex());
        return AircraftMapper.toDetail(persist(a, normalized));
    }

    public AircraftDetail updateAircraft(AircraftId id, AircraftUpdateRequest req) {
        validateAircraftType(req.aircraftTypeId().value());
        Aircraft a = loadOrThrow(id);

        String normalized = Immatriculation.of(req.immatriculation()).normalized();
        aircrafts.findActiveByImmatriculation(normalized)
                .filter(other -> !sameRow(other, id))
                .ifPresent(other -> {
                    throw new DuplicateImmatriculationException(normalized);
                });

        a.changeType(req.aircraftTypeId().value());
        a.rename(req.immatriculation());
        a.updateMasterdata(
                req.manufacturerName(),
                req.aircraftModel(),
                req.competitionSign(),
                req.flarmId(),
                req.aircraftSerialNumber(),
                req.yearOfManufacture(),
                req.noiseClass(),
                req.noiseLevel(),
                req.mtom(),
                req.nrOfSeats(),
                a.getAircraftOwnerPersonId(),
                req.flightOperatingCounterUnitTypeId(),
                req.engineOperatingCounterUnitTypeId(),
                req.homebaseId() == null ? null : req.homebaseId().value(),
                req.spotLink(),
                req.isTowingOrWinchRequired(),
                req.isTowingStartAllowed(),
                req.isWinchStartAllowed(),
                req.isTowingAircraft(),
                req.isFastEntryRecord(),
                req.comment(),
                req.daecIndex());
        return AircraftMapper.toDetail(persist(a, normalized));
    }

    public void softDeleteAircraft(AircraftId id, @Nullable UUID userId) {
        Aircraft a = loadOrThrow(id);
        a.softDelete(userId, clock);
        aircrafts.save(a);
    }

    public AircraftDetail transferOwnership(AircraftId id, AircraftTransferOwnershipRequest req) {
        Aircraft a = loadOrThrow(id);
        a.transferOwnership(
                req.newOwnerClubId() == null ? null : req.newOwnerClubId().value(),
                req.newOwnerPersonId());
        return AircraftMapper.toDetail(aircrafts.save(a));
    }

    public AircraftStateHistoryEntryResponse changeAircraftState(AircraftId id,
                                                                 AircraftStateChangeRequest req) {
        Aircraft a = loadOrThrow(id);
        validateAircraftState(req.aircraftStateId().value());
        AircraftStateHistoryEntry entry;
        try {
            entry = a.changeState(
                    req.aircraftStateId().value(),
                    req.validFrom(),
                    req.noticedByPersonId(),
                    req.remarks());
            aircrafts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // ux_aas_current_state_per_aircraft race — concurrent write closed
            // the open period under us. Surface as a typed domain conflict.
            throw new AircraftStateConflictException(
                    "Aircraft state was concurrently modified; retry the request", e);
        }
        return AircraftMapper.toStateResponse(entry);
    }

    public AircraftOperatingCounterResponse recordAircraftCounter(AircraftId id,
                                                                  AircraftCounterRecordRequest req) {
        Aircraft a = loadOrThrow(id);
        AircraftOperatingCounter counter;
        try {
            counter = a.recordCounter(
                    req.atDateTime(),
                    req.totalTowedGliderStarts(),
                    req.totalWinchLaunchStarts(),
                    req.totalSelfStarts(),
                    req.flightOperatingCounterInSeconds(),
                    req.engineOperatingCounterInSeconds(),
                    req.nextMaintenanceAtFlightOperatingCounterInSeconds(),
                    req.nextMaintenanceAtEngineOperatingCounterInSeconds());
            aircrafts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // ux_aoc_aircraft_at_date_time race — duplicate at_date_time.
            throw new CounterMonotonicityException(
                    "Counter at_date_time collides with an existing entry", e);
        }
        return AircraftMapper.toCounterResponse(counter);
    }

    @Transactional(readOnly = true)
    public AircraftStateHistory getStateHistory(AircraftId id) {
        return AircraftMapper.toStateHistory(loadOrThrow(id));
    }

    @Transactional(readOnly = true)
    public AircraftCounterHistory getCounterHistory(AircraftId id) {
        return AircraftMapper.toCounterHistory(loadOrThrow(id));
    }

    private Aircraft loadOrThrow(AircraftId id) {
        return aircrafts.findActiveById(id.value())
                .orElseThrow(() -> new AircraftNotFoundException(id));
    }

    private Aircraft persist(Aircraft a, String normalizedImmatriculation) {
        try {
            return aircrafts.save(a);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateImmatriculationException(normalizedImmatriculation);
        }
    }

    private void validateAircraftType(UUID aircraftTypeId) {
        if (!aircraftTypes.existsById(aircraftTypeId)) {
            throw new InvalidAircraftReferenceException("aircraftTypeId");
        }
    }

    private void validateAircraftState(UUID aircraftStateId) {
        if (!aircraftStates.existsById(aircraftStateId)) {
            throw new InvalidAircraftReferenceException("aircraftStateId");
        }
    }

    private static boolean sameRow(Aircraft other, AircraftId id) {
        AircraftId otherId = other.getId();
        return otherId != null && otherId.value().equals(id.value());
    }
}
