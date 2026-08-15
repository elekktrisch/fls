package ch.alpenflight.flighttypes.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeCreateRequest;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeDetail;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeListItem;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeUpdateRequest;
import ch.alpenflight.flighttypes.domain.DuplicateFlightTypeCodeException;
import ch.alpenflight.flighttypes.domain.DuplicateFlightTypeNameException;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeNotFoundException;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.platform.id.FlightTypeId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FlightTypesService {

    private static final String AUDIT_ENTITY_TYPE = "FlightType";
    private static final String NAME_UNIQUE_CONSTRAINT = "ux_flight_type_club_name";

    private final FlightTypeRepository flightTypes;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public FlightTypesService(FlightTypeRepository flightTypes,
                              Clock clock,
                              AuditTrail auditTrail) {
        this.flightTypes = flightTypes;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<FlightTypeListItem> listFlightTypes() {
        return flightTypes.findAllActive().stream()
                .map(FlightTypeMapper::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public FlightTypeDetail getFlightType(FlightTypeId id) {
        return FlightTypeMapper.toDetail(loadOrThrow(id));
    }

    public FlightTypeDetail registerFlightType(FlightTypeCreateRequest req) {
        String name = req.flightTypeName().strip();
        flightTypes.findActiveByName(name).ifPresent(existing -> {
            throw new DuplicateFlightTypeNameException(name);
        });
        requireCodeAvailable(req.flightCode(), null);

        FlightType ft = FlightType.register(
                name,
                req.flightCode(),
                req.isInstructorRequired(),
                req.isObserverPilotOrInstructorRequired(),
                req.isCheckFlight(),
                req.isPassengerFlight(),
                req.isSoloFlight(),
                req.isForGliderFlights(),
                req.isForTowFlights(),
                req.isForMotorFlights(),
                req.isFlightCostBalanceSelectable(),
                req.isCouponNumberRequired(),
                req.isForAircraftReservationType(),
                req.minNrOfAircraftSeatsRequired());
        FlightTypeDetail created = FlightTypeMapper.toDetail(persist(ft, name));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id().value(), created));
        return created;
    }

    public FlightTypeDetail updateFlightType(FlightTypeId id, FlightTypeUpdateRequest req) {
        FlightType ft = loadOrThrow(id);
        FlightTypeDetail before = FlightTypeMapper.toDetail(ft);

        String name = req.flightTypeName().strip();
        flightTypes.findActiveByName(name)
                .filter(other -> !sameRow(other, id))
                .ifPresent(other -> {
                    throw new DuplicateFlightTypeNameException(name);
                });
        requireCodeAvailable(req.flightCode(), id);

        ft.rename(name);
        ft.changeFlightCode(req.flightCode());
        ft.updateFlags(
                req.isInstructorRequired(),
                req.isObserverPilotOrInstructorRequired(),
                req.isCheckFlight(),
                req.isPassengerFlight(),
                req.isSoloFlight(),
                req.isForGliderFlights(),
                req.isForTowFlights(),
                req.isForMotorFlights(),
                req.isFlightCostBalanceSelectable(),
                req.isCouponNumberRequired(),
                req.isForAircraftReservationType());
        ft.changeMinSeats(req.minNrOfAircraftSeatsRequired());

        FlightTypeDetail after = FlightTypeMapper.toDetail(persist(ft, name));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public void softDeleteFlightType(FlightTypeId id, @Nullable UUID userId) {
        FlightType ft = loadOrThrow(id);
        FlightTypeDetail before = FlightTypeMapper.toDetail(ft);
        ft.softDelete(userId, clock);
        flightTypes.save(ft);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id.value(), before));
    }

    private FlightType loadOrThrow(FlightTypeId id) {
        return flightTypes.findActiveById(id.value())
                .orElseThrow(() -> new FlightTypeNotFoundException(id));
    }

    private FlightType persist(FlightType ft, String name) {
        try {
            FlightType saved = flightTypes.save(ft);
            flightTypes.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            String causeMessage = e.getMostSpecificCause() == null
                    ? ""
                    : String.valueOf(e.getMostSpecificCause().getMessage());
            if (causeMessage.contains(NAME_UNIQUE_CONSTRAINT)) {
                throw new DuplicateFlightTypeNameException(name);
            }
            throw e;
        }
    }

    private void requireCodeAvailable(@Nullable String rawCode, @Nullable FlightTypeId self) {
        if (rawCode == null) {
            return;
        }
        String code = rawCode.strip();
        if (code.isEmpty()) {
            return;
        }
        flightTypes.findActiveByCode(code)
                .filter(other -> self == null || !sameRow(other, self))
                .ifPresent(other -> {
                    throw new DuplicateFlightTypeCodeException(code);
                });
    }

    private static boolean sameRow(FlightType other, FlightTypeId id) {
        FlightTypeId otherId = other.getId();
        return otherId != null && otherId.value().equals(id.value());
    }
}
