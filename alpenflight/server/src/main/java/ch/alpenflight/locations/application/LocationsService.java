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

/**
 * Transactional service for the {@link Location} aggregate. ICAO uniqueness
 * is per-club since S-049b, enforced by:
 *
 * <ol>
 *   <li>service-layer pre-check (UX optimization — cleaner 409 mapping for
 *       the non-race case). The lookup itself is tenant-scoped via Hibernate's
 *       {@code @TenantId} discriminator, so a duplicate ICAO in <em>another</em>
 *       club is invisible to this check and proceeds to insert;
 *   <li>partial UNIQUE index {@code ux_location_club_icao} on
 *       {@code location(club_id, icao_code) WHERE icao_code IS NOT NULL AND
 *       deleted_on IS NULL} (source of truth — wins races, scopes uniqueness
 *       to the active per-tenant catalog and lets soft-delete-then-recreate
 *       reuse the ICAO within the same club).
 * </ol>
 *
 * <p>External signatures speak {@link LocationId} so the controller can't
 * accidentally swap a {@code Location} id for a {@code Club} / {@code Person}
 * id. The repository port still keys on raw {@link UUID} (Spring Data +
 * Hibernate prefer it that way); the service is the seam where the type
 * narrows.
 *
 * <p>Mutations emit a {@link ch.alpenflight.audit.domain.AuditAction
 * AuditAction.CREATE/UPDATE/DELETE} row via {@link AuditTrail} — the
 * before-snapshot is captured before mutation; the after-snapshot is the
 * persisted state. Failed mutations (validation, FK, race) emit no
 * success row — the {@code RequestAuditFilter} synthesises a
 * {@code failed=true} row from the HTTP layer instead.
 */
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
        LocationDetail created = LocationMapper.toDetail(persist(loc, req.icaoCode()));
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
        loc.setIcao(req.icaoCode());
        loc.setRunway(req.runwayDirection(), req.runwayLength(), req.runwayLengthUnitTypeId());
        loc.setFrequency(req.airportFrequency());
        loc.setDescription(req.description());
        loc.setSortIndicator(req.sortIndicator());
        loc.setRouteFlags(
                req.isInboundRouteRequired(),
                req.isOutboundRouteRequired(),
                req.isFastEntryRecord());
        loc.replaceInOutboundPoints(LocationMapper.toDomainPoints(req.inOutboundPoints()));
        LocationDetail after = LocationMapper.toDetail(persist(loc, req.icaoCode()));
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

    private Location persist(Location loc, @Nullable String icaoCode) {
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
