/**
 * Persons module — cross-tenant aggregate root with tenant-scoped
 * aggregate-internal child entity.
 *
 * <p><strong>Sacred-cow shape</strong>: {@link ch.alpenflight.persons.domain.Person}
 * has no {@code @TenantId} (a single Person row may belong to multiple
 * clubs); its aggregate-internal child
 * {@link ch.alpenflight.persons.domain.PersonClub} carries {@code @TenantId}
 * on {@code club_id}. Hibernate scopes the {@code personClubs} collection
 * automatically — a CLUB_ADMINISTRATOR sees only their tenant's
 * memberships; sysadmin via {@code Tenants.runAs} sees all. This shape is
 * unique to Person; no other planned aggregate replicates it.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code persons.domain} — {@link ch.alpenflight.persons.domain.Person}
 *       aggregate, {@link ch.alpenflight.persons.domain.PersonClub},
 *       repository port, value objects, domain exceptions.</li>
 *   <li>{@code persons.application} — transactional service, DTOs (records),
 *       mappers, tenant-scoped {@code member_state} read slice.</li>
 *   <li>{@code persons.web} — REST controllers + RFC 7807 exception handler.</li>
 *   <li>{@code persons.infra} — Spring Data JPA repository implementations.</li>
 * </ul>
 *
 * <p>Authorization: CLUB_ADMINISTRATOR for all {@code /api/v1/persons/**} +
 * {@code /api/v1/club/member-states} per S-159 (SYSTEM_ADMINISTRATOR stripped
 * from tenant-scoped HTTP endpoints). Cross-tenant Person ops live on
 * {@code /api/v1/admin/persons/**} — deferred until a cutover consumer
 * demands them.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.persons;
