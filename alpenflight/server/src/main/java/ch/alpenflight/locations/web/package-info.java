/**
 * Locations HTTP adapter.
 * {@link ch.alpenflight.locations.web.LocationsController} speaks
 * {@code /api/v1/locations}; {@link
 * ch.alpenflight.locations.web.LocationsExceptionHandler} translates domain
 * exceptions to RFC 7807 problem responses.
 *
 * <p>Per ADR 0023 this package depends on {@code locations.application} (the
 * service it adapts), {@code locations.domain} (for the exception types it
 * catches), and Spring web. It must NOT depend on {@code locations.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.locations.web;
