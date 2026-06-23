/**
 * Join-requests module — the {@code JoinRequest} aggregate root (S-178). A pilot
 * self-serves into a club: submits a request against the club's join code (S-177),
 * an admin approves (materializing a {@code t_user} + Person) or denies it, or the
 * pilot withdraws it. Greenfield — legacy has no join-by-code path.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@code club_id} (ADR 0008). Per ADR 0022 directive 2 the lifecycle FSM
 * (pending → approved | denied | withdrawn; the targets terminal) lives on the
 * {@link ch.alpenflight.joinrequests.domain.JoinRequest} aggregate, NOT a CHECK
 * constraint; the schema enforces only structure (PK, the {@code club_id} FK, the
 * {@code ux_join_request_alive} one-open-per-(sub,club) partial UNIQUE).
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type() OPEN}
 * module (matching {@code clubs} / {@code planning}) so the approval orchestration
 * (T-06) may reach the aggregate + its repository port from the
 * {@code persons} / {@code users} side.
 *
 * <p>Layered per ADR 0023:
 * <ul>
 *   <li>{@code joinrequests.domain} — the
 *       {@link ch.alpenflight.joinrequests.domain.JoinRequest} aggregate,
 *       {@link ch.alpenflight.joinrequests.domain.JoinRequestStatus}, the
 *       {@link ch.alpenflight.joinrequests.domain.JoinRequestRepository} port.</li>
 *   <li>{@code joinrequests.infra} — the Spring Data JPA implementation.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.joinrequests;
