/**
 * Users module — the principal-subject aggregate. One {@code User} row per
 * Keycloak identity, scoped to a home {@code club_id}.
 *
 * <p><strong>Cross-tenant aggregate, not {@code @TenantId}-scoped.</strong>
 * The User row carries the {@code club_id} a CLUB_ADMINISTRATOR sees through,
 * but the entity itself has no {@code @TenantId} attribute: scoping the
 * principal subject through Hibernate's tenant filter would chicken-and-egg
 * the JWT-to-tenant resolution path. CLUB_ADMIN scoping for {@code /api/v1/users}
 * is enforced explicitly with {@code WHERE u.club_id = :callerClub} in the
 * repository, gated by {@code @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")}.
 *
 * <p><strong>Roles do not live on the aggregate.</strong> The realm-role
 * catalogue (SYSTEM_ADMINISTRATOR / CLUB_ADMINISTRATOR / FLIGHT_OPERATOR /
 * PILOT / OFFICE_USER / GUEST) is owned by Keycloak. The application reads
 * the caller's roles from {@code realm_access.roles} on the JWT, and writes
 * role assignments through the KC admin REST API (the
 * {@link ch.alpenflight.users.infra.keycloak.KeycloakAdminClient} façade).
 *
 * <p><strong>Architectural rule (operator, 2026-05-26).</strong> User
 * management is CLUB_ADMINISTRATOR-only. SYSTEM_ADMINISTRATOR manages clubs,
 * not users. No {@code /api/v1/admin/users/**} exists — not deferred, not
 * future work. Sysadmin cutover provisioning lives in S-028; one-off prod
 * intervention goes through the Keycloak admin UI.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code users.domain} — {@link ch.alpenflight.users.domain.User}
 *       aggregate, {@link ch.alpenflight.users.domain.Role} enum,
 *       repository port, domain exceptions.</li>
 *   <li>{@code users.application} — transactional service, DTOs,
 *       {@link ch.alpenflight.users.application.RoleAssignmentPolicy}
 *       (privilege-escalation matrix), SpEL access bean.</li>
 *   <li>{@code users.web} — REST controller + RFC 7807 exception handler.</li>
 *   <li>{@code users.infra} — Spring Data JPA repository + the Keycloak
 *       admin-REST machine client (typed façade, service-account token
 *       supplier, bearer-token + redaction interceptors on a Spring
 *       {@code RestClient}).</li>
 * </ul>
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module so dashboard-oriented read surfaces (the
 * {@code me} module's sysadmin {@code system-dashboard}, J-3 T-10) may call
 * {@code users.application.UsersService} for a cross-tenant published count
 * ({@code countAllActiveUsers()}) without a named interface — matching the
 * sibling business modules {@code clubs} + {@code flights}, which are OPEN for
 * the same compose-published-counts pattern. The dependency direction is
 * {@code me}&rarr;{@code users}; users knows nothing of any consumer.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.users;
