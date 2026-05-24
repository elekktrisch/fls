package ch.alpenflight.flights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Bundle of editable operational fields handed to {@link Flight} factories
 * and {@link Flight#updateOperationalData}. Carrier-only; validation lives
 * on the aggregate. State-machine columns are deliberately absent (S-059
 * owns those).
 */
public record FlightOperationalData(
        @Nullable LocalDate flightDate,
        @Nullable Instant startDateTime,
        @Nullable Instant ldgDateTime,
        @Nullable Instant blockStartDateTime,
        @Nullable Instant blockEndDateTime,
        @Nullable UUID startLocationId,
        @Nullable UUID ldgLocationId,
        @Nullable String startRunway,
        @Nullable String ldgRunway,
        @Nullable String outboundRoute,
        @Nullable String inboundRoute,
        @Nullable UUID flightTypeId,
        @Nullable UUID startTypeId,
        @Nullable Short nrOfLdgs,
        @Nullable Short nrOfLdgsOnStartLocation,
        boolean noStartTimeInformation,
        boolean noLdgTimeInformation,
        @Nullable Long engineStartOperatingCounterInSeconds,
        @Nullable Long engineEndOperatingCounterInSeconds,
        @Nullable String comment,
        @Nullable String incidentComment,
        @Nullable String couponNumber,
        @Nullable UUID flightCostBalanceTypeId,
        @Nullable Short nrOfPassengers,
        @Nullable Short startPosition,
        boolean soloFlight) {}
