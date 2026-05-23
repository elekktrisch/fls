/**
 * Persons use-case orchestration. Transactional service, request / response
 * DTOs (records), domain-to-DTO mapper, and the {@code MemberStateSlice}
 * read-side helper for the tenant-scoped listitem endpoint.
 *
 * <p>Per ADR 0023 this layer depends on {@code persons.domain} (aggregate +
 * the {@link ch.alpenflight.persons.domain.PersonRepository} port) and on
 * Spring's transaction + DI infrastructure. It must NOT depend on
 * {@code persons.web} or {@code persons.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.persons.application;
