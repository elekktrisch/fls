package ch.alpenflight.flights.domain;

import java.util.UUID;

public interface FlightInitialStateProvider {

    UUID initialProcessStateId();
}
