package ch.alpenflight.flights.application;

/**
 * Tenant-scoped flight counts for the club-admin dashboard tiles (J-3 T-08).
 * Both numbers are scoped to the caller's club by Hibernate's {@code @TenantId}
 * discriminator on {@code Flight.operatingClubId} (ADR 0008) — they can never
 * cross clubs.
 *
 * <p>Published cross-module read shape: the {@code me} module's club-dashboard
 * endpoint calls {@link FlightsService#clubFlightCounts()} and projects this
 * onto its wire DTO, rather than reaching into {@code flights} internals.
 *
 * @param todaysFlights     non-deleted flights whose {@code flight_date} is
 *                          today (the caller's club only)
 * @param pendingValidation non-deleted flights in process state
 *                          {@code NotProcessed} or {@code Invalid} (the
 *                          caller's club only)
 */
public record ClubFlightCounts(long todaysFlights, long pendingValidation) {}
