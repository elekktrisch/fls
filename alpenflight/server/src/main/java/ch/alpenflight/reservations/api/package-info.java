/**
 * Reservations module — published cross-module API surface (J-26 T-16). A
 * {@link org.springframework.modulith.NamedInterface named interface} so another
 * module (e.g. {@code planning}) depends on a deliberate, narrow contract
 * instead of reaching into {@code reservations.domain} / {@code .infra}
 * internals.
 *
 * <p>Today it carries the single
 * {@link ch.alpenflight.reservations.api.ReservationCountPort} — the per-day
 * aircraft-reservation count {@code planning} consumes for its
 * {@code NumberOfAircraftReservations} projection (J-6 behavior). The port lets
 * {@code planning} read the figure through the reservations domain API rather
 * than the retired {@code planning-day-reservation-count} native SQL probe (the
 * register entry's own "Remove when").
 */
@org.springframework.modulith.NamedInterface("reservation-count")
@org.jspecify.annotations.NullMarked
package ch.alpenflight.reservations.api;
