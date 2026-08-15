package ch.alpenflight.flights.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface FlightReportDecorations {

    @Nullable String immatriculation(@Nullable UUID aircraftId);

    @Nullable String personName(@Nullable UUID personId);

    @Nullable FlightTypeDecoration flightType(@Nullable UUID flightTypeId);

    @Nullable String locationName(@Nullable UUID locationId);

    @Nullable String startTypeCode(@Nullable UUID startTypeId);

    record FlightTypeDecoration(@Nullable String flightCode, String flightTypeName) {}
}
