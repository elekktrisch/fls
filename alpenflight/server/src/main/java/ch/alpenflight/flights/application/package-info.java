/**
 * Flight use-case orchestration. Transactional service, request / response
 * DTOs, the domain-to-DTO mapper, the keyset-cursor encode / decode helper.
 *
 * <p>Per ADR 0023 this layer depends on {@code flights.domain} and on
 * Spring's transaction + DI infrastructure. It must NOT depend on
 * {@code flights.web} or {@code flights.infra}.
 *
 * <p>Mass-assignment defense (A04): {@code FlightCreateRequest} and
 * {@code FlightUpdateRequest} explicitly exclude the state-machine columns
 * ({@code processStateId}, {@code validatedOn}, {@code deliveryCreatedOn},
 * {@code flightReportSentOn}, {@code validationErrors}), the tenant column
 * ({@code operatingClubId} — set by Hibernate from the JWT), and audit
 * metadata. Air-state is computed (S-060), response-only; the request DTOs
 * carry no air-state field at all. Discriminator {@code flightAircraftType}
 * is immutable post-create.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights.application;
