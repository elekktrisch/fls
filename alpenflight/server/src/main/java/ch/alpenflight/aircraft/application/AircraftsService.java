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
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
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
import ch.alpenflight.referencedata.domain.CounterUnitTypeRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * <p>Mutations emit {@link AuditAction#CREATE} / {@link AuditAction#UPDATE} /
 * {@link AuditAction#DELETE} via {@link AuditTrail}; state transitions and
 * counter records emit {@link AuditAction#STATE_TRANSITION} /
 * {@link AuditAction#UPDATE} respectively. The before-snapshot is the
 * response DTO of the prior state; the after-snapshot is the persisted DTO.
 */
@Service
@Transactional
public class AircraftsService {

    private static final String AUDIT_ENTITY_TYPE = "Aircraft";

    private final AircraftRepository aircrafts;
    private final AircraftTypeRepository aircraftTypes;
    private final AircraftStateRepository aircraftStates;
    private final CounterUnitTypeRepository counterUnitTypes;
    private final AircraftAccess access;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public AircraftsService(AircraftRepository aircrafts,
                            AircraftTypeRepository aircraftTypes,
                            AircraftStateRepository aircraftStates,
                            CounterUnitTypeRepository counterUnitTypes,
                            AircraftAccess access,
                            Clock clock,
                            AuditTrail auditTrail) {
        this.aircrafts = aircrafts;
        this.aircraftTypes = aircraftTypes;
        this.aircraftStates = aircraftStates;
        this.counterUnitTypes = counterUnitTypes;
        this.access = access;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<AircraftListItem> listAircraft(@Nullable AircraftTypeSlice slice) {
        List<AircraftRepository.ListRow> rows = switch (slice == null ? null : slice) {
            case null -> aircrafts.findAllActiveListRows();
            case GLIDER -> aircrafts.findActiveListRowsByTypeCodeIn(GLIDER_CODES);
            case MOTOR -> aircrafts.findActiveListRowsByTypeCodeIn(MOTOR_CODES);
            case TOWING -> aircrafts.findActiveTowingListRows();
        };
        return rows.stream().map(AircraftMapper::toListItem).toList();
    }

    // Preserves legacy AircraftService.cs:303-304 membership: a glider with
    // motor still flies as a glider, so it stays in the glider slice.
    private static final Set<String> GLIDER_CODES = Set.of("GLIDER", "GLIDER_WITH_MOTOR");

    // Preserves legacy AircraftService.cs:96 (AircraftTypeId >= MotorGlider)
    // — pure-motor types from legacy_int_id >= 4. GLIDER_WITH_MOTOR is
    // intentionally excluded (legacy_int_id = 2).
    private static final Set<String> MOTOR_CODES = Set.of(
            "MOTOR_GLIDER", "MOTOR_AIRCRAFT", "MULTI_ENGINE", "JET", "HELICOPTER");

    @Transactional(readOnly = true)
    public List<AircraftPickerItem> listAircraftForPicker() {
        return aircrafts.findAllActivePickerRows().stream()
                .map(AircraftMapper::toPickerItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public AircraftDetail getAircraft(AircraftId id) {
        Aircraft a = loadOrThrow(id);
        boolean ownerVisible = access.canSeeOwnerOnlyFields(currentAuth(), a.getOwnerClubId());
        return AircraftMapper.toDetail(a, ownerVisible);
    }

    public AircraftDetail registerAircraft(AircraftCreateRequest req) {
        validateAircraftType(req.aircraftTypeId().value());
        validateCounterUnitType(req.flightOperatingCounterUnitTypeId());
        validateCounterUnitType(req.engineOperatingCounterUnitTypeId());
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
                req.comment(),
                req.daecIndex());
        // The mutating principal is already SYS_ADMIN or CLUB_ADMIN-of-owner
        // (gated at the controller); they may see owner-only fields.
        AircraftDetail created = AircraftMapper.toDetail(persist(a, normalized), true);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id().value(), created));
        return created;
    }

    public AircraftDetail updateAircraft(AircraftId id, AircraftUpdateRequest req) {
        validateAircraftType(req.aircraftTypeId().value());
        validateCounterUnitType(req.flightOperatingCounterUnitTypeId());
        validateCounterUnitType(req.engineOperatingCounterUnitTypeId());
        Aircraft a = loadOrThrow(id);
        AircraftDetail before = AircraftMapper.toDetail(a, true);

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
                req.comment(),
                req.daecIndex());
        // The mutating principal is already SYS_ADMIN or CLUB_ADMIN-of-owner
        // (gated at the controller); they may see owner-only fields.
        AircraftDetail after = AircraftMapper.toDetail(persist(a, normalized), true);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public void softDeleteAircraft(AircraftId id, @Nullable UUID userId) {
        Aircraft a = loadOrThrow(id);
        AircraftDetail before = AircraftMapper.toDetail(a, true);
        a.softDelete(userId, clock);
        aircrafts.save(a);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id.value(), before));
    }

    public AircraftDetail transferOwnership(AircraftId id, AircraftTransferOwnershipRequest req) {
        Aircraft a = loadOrThrow(id);
        AircraftDetail before = AircraftMapper.toDetail(a, true);
        a.transferOwnership(
                req.newOwnerClubId() == null ? null : req.newOwnerClubId().value(),
                req.newOwnerPersonId());
        // Transfer endpoint is SYS_ADMIN-only; owner-only fields visible.
        AircraftDetail after = AircraftMapper.toDetail(aircrafts.save(a), true);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public AircraftStateHistoryEntryResponse changeAircraftState(AircraftId id,
                                                                 AircraftStateChangeRequest req) {
        Aircraft a = loadOrThrow(id);
        validateAircraftState(req.aircraftStateId().value());
        AircraftStateHistoryEntry entry;
        try {
            // Close-then-flush-then-open: the partial-unique index
            // ux_aas_current_state_per_aircraft rejects two open rows, and
            // Hibernate's default flush order inserts before updating —
            // so the close must be flushed before the new INSERT.
            a.closeCurrentStatePeriodAt(req.validFrom());
            aircrafts.flush();
            entry = a.openStatePeriod(
                    req.aircraftStateId().value(),
                    req.validFrom(),
                    req.noticedByPersonId(),
                    req.remarks());
            aircrafts.flush();
        } catch (DataIntegrityViolationException e) {
            // ux_aas_current_state_per_aircraft race — concurrent write closed
            // the open period under us. Surface as a typed domain conflict.
            throw new AircraftStateConflictException(
                    "Aircraft state was concurrently modified; retry the request", e);
        }
        AircraftStateHistoryEntryResponse stateResponse = AircraftMapper.toStateResponse(entry);
        auditTrail.record(AuditAction.STATE_TRANSITION,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, id.value(), stateResponse));
        return stateResponse;
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
            aircrafts.flush();
        } catch (DataIntegrityViolationException e) {
            // ux_aoc_aircraft_at_date_time race — duplicate at_date_time.
            throw new CounterMonotonicityException(
                    "Counter at_date_time collides with an existing entry", e);
        }
        AircraftOperatingCounterResponse counterResponse = AircraftMapper.toCounterResponse(counter);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, id.value(), counterResponse));
        return counterResponse;
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

    private void validateCounterUnitType(@Nullable UUID counterUnitTypeId) {
        if (counterUnitTypeId == null) {
            return;
        }
        if (!counterUnitTypes.existsById(counterUnitTypeId)) {
            throw new InvalidAircraftReferenceException("counterUnitTypeId");
        }
    }

    private static boolean sameRow(Aircraft other, AircraftId id) {
        AircraftId otherId = other.getId();
        return otherId != null && otherId.value().equals(id.value());
    }

    private static @Nullable Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
