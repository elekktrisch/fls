/**
 * Flight HTTP adapter. {@link ch.alpenflight.flights.web.FlightsController}
 * speaks {@code /api/v1/flights}; the local {@code @RestControllerAdvice}
 * translates domain exceptions to RFC 7807 problem responses.
 *
 * <p>Per ADR 0023 this package depends on {@code flights.application} (the
 * service it adapts), {@code flights.domain} (for the exception types), and
 * Spring web. It must NOT depend on {@code flights.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights.web;
