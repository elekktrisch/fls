package ch.alpenflight.flights.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CrewMemberSpec(
        UUID personId,
        UUID flightCrewTypeId,
        @Nullable Instant beginFlightDatetime,
        @Nullable Instant endFlightDatetime,
        @Nullable Instant beginInstructionDatetime,
        @Nullable Instant endInstructionDatetime,
        @Nullable Short nrOfLdgs,
        @Nullable Short nrOfStarts) {}
