package ch.alpenflight.flights.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published by {@link FlightsService#createFlight} after a flight is saved,
 * once the surrounding {@code @Transactional} method commits. The thinnest
 * concrete consumer that proves the S-176 live-update channel (J-3): a
 * listener in the {@code me} module translates it to a {@code "flight.created"}
 * Server-Sent Event on the creating principal's stream so an open dashboard
 * tile refreshes without a reload.
 *
 * <p><strong>Module boundary.</strong> This is the {@code flights} module's
 * published event; {@code flights} stays ignorant of {@code me}. The
 * dependency direction is {@code me}&rarr;{@code flights} (the listener imports
 * this type), never the reverse — the modulith-idiomatic cross-module channel
 * (ADR 0018), the same shape the audit trail uses for deployment lifecycle
 * transitions.
 *
 * <p><strong>{@code creatorSub} captured at publish time.</strong> The SSE
 * fan-out runs in an {@code @TransactionalEventListener(AFTER_COMMIT)} after the
 * {@code SecurityContext} may have been cleared, so the creating Keycloak
 * {@code sub} is captured here on the publishing (request) thread — the same
 * reason {@code AuditTrailService} captures its actor at publish time. Nullable
 * because a flight may be created by a non-JWT / system flow (no open dashboard
 * to nudge); the listener then no-ops.
 *
 * <p>Payload is deliberately small: enough for the tile to know a flight
 * appeared and refetch its count — SSE is the change overlay, not the source of
 * truth (every tile loads its state via a normal GET on first paint).
 */
public record FlightCreatedEvent(UUID flightId,
                                 @Nullable UUID operatingClubId,
                                 @Nullable String creatorSub) {
}
