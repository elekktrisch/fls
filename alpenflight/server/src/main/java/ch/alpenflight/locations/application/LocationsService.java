package ch.alpenflight.locations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.application.LocationDtos.LocationUpdateRequest;
import ch.alpenflight.locations.domain.IcaoCodeAlreadyExistsException;
import ch.alpenflight.locations.domain.InvalidLocationReferenceException;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationNotFoundException;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LocationsService {

    private static final String AUDIT_ENTITY_TYPE = "Location";

    private final LocationRepository locations;
    private final LocationTypeRepository locationTypes;
    private final CountryRepository countries;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public LocationsService(LocationRepository locations,
                            LocationTypeRepository locationTypes,
                            CountryRepository countries,
                            Clock clock,
                            AuditTrail auditTrail) {
        this.locations = locations;
        this.locationTypes = locationTypes;
        this.countries = countries;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<LocationListItem> listLocations() {
        return locations.findAllActiveListRows().stream()
                .map(LocationMapper::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationDetail getLocation(LocationId id) {
        return locations.findActiveByIdWithPoints(id.value())
                .map(LocationMapper::toDetail)
                .orElseThrow(() -> new LocationNotFoundException(id));
    }

    public LocationDetail createLocation(LocationCreateRequest req) {
        validateReferences(req.countryId().value(), req.locationTypeId().value());
        String icao = req.icaoCode();
        if (icao != null) {
            locations.findActiveByIcaoCodeIgnoreCase(icao)
                    .ifPresent(existing -> {
                        throw new IcaoCodeAlreadyExistsException(icao);
                    });
        }
        Location loc = Location.create(
                req.locationName(),
                req.locationShortName(),
                req.countryId().value(),
                req.locationTypeId().value(),
                req.icaoCode(),
                req.latitude(),
                req.longitude(),
                req.elevation(),
                req.elevationUnitTypeId(),
                req.runwayDirection(),
                req.runwayLength(),
                req.runwayLengthUnitTypeId(),
                req.airportFrequency(),
                req.description(),
                req.sortIndicator(),
                req.isInboundRouteRequired(),
                req.isOutboundRouteRequired(),
                req.isFastEntryRecord());
        loc.replaceInOutboundPoints(LocationMapper.toDomainPoints(req.inOutboundPoints()));
        LocationDetail created = LocationMapper.toDetail(
                saveTranslatingIcaoUniqueViolationToConflict(loc, req.icaoCode()));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id().value(), created));
        return created;
    }

    public LocationDetail updateLocation(LocationId id, LocationUpdateRequest req) {
        validateReferences(req.countryId().value(), req.locationTypeId().value());
        Location loc = locations.findActiveByIdWithPoints(id.value())
                .orElseThrow(() -> new LocationNotFoundException(id));
        LocationDetail before = LocationMapper.toDetail(loc);
        String icao = req.icaoCode();
        if (icao != null) {
            locations.findActiveByIcaoCodeIgnoreCase(icao)
                    .filter(other -> !sameRow(other, id))
                    .ifPresent(other -> {
                        throw new IcaoCodeAlreadyExistsException(icao);
                    });
        }
        loc.rename(req.locationName(), req.locationShortName());
        loc.relocate(
                req.countryId().value(),
                req.locationTypeId().value(),
                req.latitude(),
                req.longitude(),
                req.elevation(),
                req.elevationUnitTypeId());
        loc.setIcaoValidatingOnlyAChangedValue(req.icaoCode());
        loc.setRunway(req.runwayDirection(), req.runwayLength(), req.runwayLengthUnitTypeId());
        loc.setFrequency(req.airportFrequency());
        loc.setDescription(req.description());
        loc.setSortIndicator(req.sortIndicator());
        loc.setRouteFlags(
                req.isInboundRouteRequired(),
                req.isOutboundRouteRequired(),
                req.isFastEntryRecord());
        loc.replaceInOutboundPoints(LocationMapper.toDomainPoints(req.inOutboundPoints()));
        LocationDetail after = LocationMapper.toDetail(
                saveTranslatingIcaoUniqueViolationToConflict(loc, req.icaoCode()));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id.value(), before, after));
        return after;
    }

    public void softDeleteLocation(LocationId id, @Nullable UUID userId) {
        Location loc = locations.findActiveByIdWithPoints(id.value())
                .orElseThrow(() -> new LocationNotFoundException(id));
        LocationDetail before = LocationMapper.toDetail(loc);
        loc.softDelete(userId, clock);
        locations.save(loc);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id.value(), before));
    }

    private Location saveTranslatingIcaoUniqueViolationToConflict(Location loc,
                                                                  @Nullable String icaoCode) {
        try {
            return locations.save(loc);
        } catch (DataIntegrityViolationException e) {
            if (icaoCode != null) {
                throw new IcaoCodeAlreadyExistsException(icaoCode);
            }
            throw e;
        }
    }

    private void validateReferences(UUID countryId, UUID locationTypeId) {
        if (!countries.existsById(countryId)) {
            throw new InvalidLocationReferenceException("countryId");
        }
        if (!locationTypes.existsById(locationTypeId)) {
            throw new InvalidLocationReferenceException("locationTypeId");
        }
    }

    private static boolean sameRow(Location loc, LocationId id) {
        return loc.getId() != null && loc.getId().value().equals(id.value());
    }
}
