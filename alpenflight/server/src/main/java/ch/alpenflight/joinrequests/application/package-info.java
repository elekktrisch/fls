/**
 * Join-requests application layer (S-178). Holds {@link
 * ch.alpenflight.joinrequests.application.JoinRequestsService} — the
 * transactional orchestration for submit / withdraw / me-read / admin
 * pending-list — its DTOs, and the application exception vocabulary
 * ({@code UnknownJoinCodeException} → 404, {@code AlreadyClubMemberException}
 * → 409, {@code JoinRequestNotFoundException} → 404, {@code
 * NotJoinRequestOwnerException} → 403).
 *
 * <p>Per ADR 0023 this layer depends on {@code joinrequests.domain} and the
 * OPEN sibling modules {@code clubs.domain} (code → club resolution),
 * {@code users.domain} (the one-sub-one-club 409 check), {@code audit.domain},
 * and {@code platform.tenancy} (the {@code Tenants.runAs} window that scopes the
 * tenant-less pilot's writes to the resolved club). It must NOT depend on
 * {@code joinrequests.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.joinrequests.application;
