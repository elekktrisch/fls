package ch.alpenflight.flights.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FlightCreatedEvent(UUID flightId,
                                 @Nullable UUID operatingClubId,
                                 @Nullable String creatorSub) {
}
