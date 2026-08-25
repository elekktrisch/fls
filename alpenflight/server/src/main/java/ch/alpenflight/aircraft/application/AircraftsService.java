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
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.referencedata.domain.AircraftStateRepository;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import ch.alpenflight.referencedata.domain.CounterUnitTypeRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AircraftsService {

    private static final String AUDIT_ENTITY_TYPE = "Aircraft";

    private final AircraftRepository aircrafts;
    private final AircraftTypeRepository aircraftTypes;
    private final AircraftStateRepository aircraftStates;
    private final CounterUnitTypeRepository counterUnitTypes;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final AircraftAccess aircraftAccess;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public AircraftsService(AircraftRepository aircrafts,
                            AircraftTypeRepository aircraftTypes,
                            AircraftStateRepository aircraftStates,
                            CounterUnitTypeRepository counterUnitTypes,
                            ClubTenantIdentifierResolver tenantResolver,
                            AircraftAccess aircraftAccess,
                            Clock clock,
                            AuditTrail auditTrail) {
        this.aircrafts = aircrafts;
        this.aircraftTypes = aircraftTypes;
        this.aircraftStates = aircraftStates;
        this.counterUnitTypes = counterUnitTypes;
        this.tenantResolver = tenantResolver;
        this.aircraftAccess = aircraftAccess;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<AircraftListItem> listAircraft(@Nullable AircraftTypeSlice slice) {
        UUID readingClubId = tenantResolver.resolveCurrentTenantIdentifier();
        List<AircraftRepository.ListRow> rows = switch (slice == null ? null : slice) {
            case null -> aircrafts.findActiveListRowsInSameDeploymentAs(readingClubId);
            case GLIDER -> aircrafts.findActiveListRowsByTypeCodeInSameDeploymentAs(
                    GLIDER_CODES, readingClubId);
            case MOTOR -> aircrafts.findActiveListRowsByTypeCodeInSameDeploymentAs(
                    MOTOR_CODES, readingClubId);
            case TOWING -> aircrafts.findActiveTowingListRowsInSameDeploymentAs(readingClubId);
        };
        return rows.stream().map(AircraftMapper::toListItem).toList();
    }

    private static final Set<String> GLIDER_CODES = Set.of("GLIDER", "GLIDER_WITH_MOTOR");

    private static final Set<String> MOTOR_CODES = Set.of(
            "MOTOR_GLIDER", "MOTOR_AIRCRAFT", "MULTI_ENGINE", "JET", "HELICOPTER");

    @Transactional(readOnly = true)
    public List<AircraftPickerItem> listAircraftForPicker() {
        return aircrafts.findActivePickerRowsInSameDeploymentAs(
                        tenantResolver.resolveCurrentTenantIdentifier()).stream()
                .map(AircraftMapper::toPickerItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public AircraftDetail getAircraft(AircraftId id) {
        Aircraft a = loadVisibleToTheReaderOrThrow(id);
        boolean includeLatestCounter =
                aircraftAccess.canViewManagerOnlyData(a.getManagingClubId());
        return AircraftMapper.toDetail(a, includeLatestCounter);
    }

    public AircraftDetail registerAircraft(AircraftCreateRequest req) {
        validateAircraftType(req.aircraftTypeId().value());
        validateCounterUnitType(req.flightOperatingCounterUnitTypeId());
        validateCounterUnitType(req.engineOperatingCounterUnitTypeId());
        String normalized = Immatriculation.of(req.immatriculation()).storedUppercaseKeepingHyphens();
        aircrafts.findActiveByImmatriculation(normalized)
                .ifPresent(existing -> {
                    throw new DuplicateImmatriculationException(normalized);
                });

        UUID callerClubId = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(callerClubId)) {
            throw new IllegalStateException(
                    "Aircraft.register requires a tenant context; unscoped caller cannot register");
        }
        UUID managingClubId = callerClubId;
        UUID defaultOwnerClubId = callerClubId;

        Aircraft a = Aircraft.register(
                managingClubId,
                defaultOwnerClubId,
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
                null,
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
        AircraftDetail created = AircraftMapper.toDetail(persist(a, normalized));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id().value(), created));
        return created;
    }

    public AircraftDetail updateAircraft(AircraftId id, AircraftUpdateRequest req) {
        validateAircraftType(req.aircraftTypeId().value());
        validateCounterUnitType(req.flightOperatingCounterUnitTypeId());
        validateCounterUnitType(req.engineOperatingCounterUnitTypeId());
        Aircraft a = loadGatedByAircraftAccessOrThrow(id);
        AircraftDetail before = AircraftMapper.toDetail(a);

        String normalized = Immatriculation.of(req.immatriculation()).storedUppercaseKeepingHyphens();
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
        AircraftDetail after = AircraftMapper.toDetail(persist(a, normalized));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public void softDeleteAircraft(AircraftId id, @Nullable UUID userId) {
        Aircraft a = loadGatedByAircraftAccessOrThrow(id);
        AircraftDetail before = AircraftMapper.toDetail(a);
        a.softDelete(userId, clock);
        aircrafts.save(a);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id.value(), before));
    }

    public AircraftDetail transferOwnership(AircraftId id, AircraftTransferOwnershipRequest req) {
        Aircraft a = loadGatedByAircraftAccessOrThrow(id);
        UUID newOwnerClubId = req.newOwnerClubId() == null ? null : req.newOwnerClubId().value();
        AircraftDetail before = AircraftMapper.toDetail(a);
        a.transferOwnership(newOwnerClubId, req.newOwnerPersonId());
        AircraftDetail after;
        try {
            after = AircraftMapper.toDetail(aircrafts.save(a));
            aircrafts.flush();
        } catch (DataIntegrityViolationException e) {
            String causeMessage = e.getMostSpecificCause() == null
                    ? ""
                    : String.valueOf(e.getMostSpecificCause().getMessage());
            if (causeMessage.contains(OWNER_CLUB_FOREIGN_KEY_CONSTRAINT)) {
                throw new InvalidAircraftReferenceException("newOwnerClubId");
            }
            throw e;
        }
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public AircraftStateHistoryEntryResponse changeAircraftState(AircraftId id,
                                                                 AircraftStateChangeRequest req) {
        Aircraft a = loadGatedByAircraftAccessOrThrow(id);
        validateAircraftState(req.aircraftStateId().value());
        AircraftStateHistoryEntry entry;
        try {
            a.closeCurrentStatePeriodAt(req.validFrom());
            flushSoNoTwoStatePeriodsAreOpenAtOnce();
            entry = a.openStatePeriod(
                    req.aircraftStateId().value(),
                    req.validFrom(),
                    req.noticedByPersonId(),
                    req.remarks());
            aircrafts.flush();
        } catch (DataIntegrityViolationException e) {
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
        Aircraft a = loadGatedByAircraftAccessOrThrow(id);
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
        return AircraftMapper.toStateHistory(loadVisibleToTheReaderOrThrow(id));
    }

    @Transactional(readOnly = true)
    public AircraftCounterHistory getCounterHistory(AircraftId id) {
        return AircraftMapper.toCounterHistory(loadGatedByAircraftAccessOrThrow(id));
    }

    private void flushSoNoTwoStatePeriodsAreOpenAtOnce() {
        aircrafts.flush();
    }

    private Aircraft loadVisibleToTheReaderOrThrow(AircraftId id) {
        return aircrafts.findActiveByIdInSameDeploymentAs(
                        id.value(), tenantResolver.resolveCurrentTenantIdentifier())
                .orElseThrow(() -> new AircraftNotFoundException(id));
    }

    private Aircraft loadGatedByAircraftAccessOrThrow(AircraftId id) {
        return aircrafts.findActiveById(id.value())
                .orElseThrow(() -> new AircraftNotFoundException(id));
    }

    private static final String IMMATRICULATION_UNIQUE_CONSTRAINT = "ux_aircraft_immatriculation";

    private static final String OWNER_CLUB_FOREIGN_KEY_CONSTRAINT = "fk_aircraft_owner_club_id";

    private Aircraft persist(Aircraft a, String normalizedImmatriculation) {
        try {
            Aircraft saved = aircrafts.save(a);
            aircrafts.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            String causeMessage = e.getMostSpecificCause() == null
                    ? ""
                    : String.valueOf(e.getMostSpecificCause().getMessage());
            if (causeMessage.contains(IMMATRICULATION_UNIQUE_CONSTRAINT)) {
                throw new DuplicateImmatriculationException(normalizedImmatriculation);
            }
            throw e;
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
}
