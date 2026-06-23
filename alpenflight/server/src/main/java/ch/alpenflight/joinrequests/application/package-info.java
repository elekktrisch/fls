/**
 * Join-requests application layer (S-178). Holds {@link
 * ch.alpenflight.joinrequests.application.JoinRequestsService} (submit / withdraw
 * / me-read / admin pending-list) and {@link
 * ch.alpenflight.joinrequests.application.JoinRequestDecisionsService} (the
 * CLUB_ADMINISTRATOR approve / deny cross-system orchestration), their DTOs, and
 * the application exception vocabulary ({@code UnknownJoinCodeException} → 404,
 * {@code AlreadyClubMemberException} → 409, {@code JoinRequestNotFoundException}
 * → 404, {@code NotJoinRequestOwnerException} → 403, {@code
 * CrossTenantPersonLinkException} → 409).
 *
 * <p>Per ADR 0023 this layer depends on {@code joinrequests.domain} and the
 * OPEN sibling modules {@code clubs.domain} (code → club resolution),
 * {@code users.domain} + {@code users.application} (the one-sub-one-club 409
 * check, the {@code UserDirectoryPort} KC attribute / role writes, the
 * {@code RoleAssignmentPolicy} escalation gate, {@code t_user} create),
 * {@code persons.domain} (auto-create / link Person + PersonClub),
 * {@code audit.domain}, {@code platform.tenancy} (the {@code Tenants.runAs}
 * window that scopes the tenant-less pilot's writes to the resolved club, plus
 * {@code LanguageCodeLookup} for the new member's default language),
 * {@code platform.mail} (the transactional join-request emails), and the OPEN
 * {@code me.application} publish port (the {@code join-request.status-changed}
 * SSE fan-out — the producer&rarr;{@code me} direction the bus contract
 * documents). It must NOT depend on {@code joinrequests.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.joinrequests.application;
