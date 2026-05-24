/**
 * Persons domain: the {@code Person} aggregate root, its aggregate-internal
 * {@code PersonClub} child entity, the {@link ch.alpenflight.persons.domain.PersonRepository}
 * port, and typed domain exceptions.
 *
 * <p><strong>Dependency rule (ADR 0023):</strong> no Spring-web, no Jackson,
 * no Spring Data — only JDK + Jakarta Persistence + Hibernate {@code @TenantId}
 * + JSpecify nullness markers + the typed-id family from
 * {@code platform.id}. Domain exceptions ride to HTTP via the
 * {@code web/}-package {@code @RestControllerAdvice}; the exception classes
 * themselves stay annotation-free here.
 *
 * <p><strong>Cross-tenant sacred cow:</strong> {@code Person} carries no
 * {@code @TenantId}; PK-load works across all tenants (S-058 Flight crew
 * load depends on this). {@code PersonClub} carries {@code @TenantId} on
 * {@code clubId}, so Hibernate automatically scopes the aggregate's
 * {@code personClubs} collection to the caller's tenant.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.persons.domain;
