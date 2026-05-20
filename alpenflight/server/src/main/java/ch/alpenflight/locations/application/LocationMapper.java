package ch.alpenflight.locations.application;

import ch.alpenflight.locations.application.LocationDtos.InOutboundPointRequest;
import ch.alpenflight.locations.application.LocationDtos.InOutboundPointResponse;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.domain.InOutboundPoint;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class LocationMapper {

    private LocationMapper() {}

    static LocationListItem toListItem(LocationRepository.ListRow row) {
        return new LocationListItem(
                LocationId.of(row.id()),
                row.locationName(),
                row.locationShortName(),
                row.icaoCode(),
                row.locationTypeCode(),
                row.isAirfield(),
                row.isFastEntryRecord());
    }

    static LocationDetail toDetail(Location loc) {
        return new LocationDetail(
                Objects.requireNonNull(loc.getId(), "Cannot map an unpersisted Location"),
                loc.getLocationName(),
                loc.getLocationShortName(),
                Objects.requireNonNull(CountryId.ofNullable(loc.getCountryId()),
                        "Location is missing countryId (NOT NULL invariant in V3)"),
                Objects.requireNonNull(LocationTypeId.ofNullable(loc.getLocationTypeId()),
                        "Location is missing locationTypeId (NOT NULL invariant in V3)"),
                loc.getIcaoCode(),
                loc.getLatitude(),
                loc.getLongitude(),
                loc.getElevation(),
                loc.getElevationUnitTypeId(),
                loc.getRunwayDirection(),
                loc.getRunwayLength(),
                loc.getRunwayLengthUnitTypeId(),
                loc.getAirportFrequency(),
                loc.getDescription(),
                loc.getSortIndicator(),
                loc.isInboundRouteRequired(),
                loc.isOutboundRouteRequired(),
                loc.isFastEntryRecord(),
                loc.getInOutboundPoints().stream()
                        .map(LocationMapper::toResponse)
                        .toList());
    }

    static InOutboundPointResponse toResponse(InOutboundPoint p) {
        return new InOutboundPointResponse(
                Objects.requireNonNull(p.getId(), "Cannot map an unpersisted InOutboundPoint"),
                p.getPointName(),
                p.getPointType(),
                p.getDirection(),
                p.getDescription());
    }

    static List<InOutboundPoint> toDomainPoints(@Nullable List<InOutboundPointRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.stream()
                .map(r -> Location.newInOutboundPoint(
                        r.pointName(), r.pointType(), r.direction(), r.description()))
                .toList();
    }
}
