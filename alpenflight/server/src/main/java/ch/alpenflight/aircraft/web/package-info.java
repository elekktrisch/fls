/**
 * Aircraft HTTP adapter.
 * {@link ch.alpenflight.aircraft.web.AircraftsController} speaks
 * {@code /api/v1/aircraft}; the local {@code @RestControllerAdvice}
 * translates domain exceptions to RFC 7807 problem responses.
 *
 * <p>Per ADR 0023 this package depends on {@code aircraft.application}
 * (the service it adapts), {@code aircraft.domain} (for the exception
 * types), and Spring web. It must NOT depend on {@code aircraft.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.web;
