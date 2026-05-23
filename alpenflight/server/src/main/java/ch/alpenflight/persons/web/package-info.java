/**
 * Persons HTTP adapter.
 * {@link ch.alpenflight.persons.web.PersonsController} speaks
 * {@code /api/v1/persons/**};
 * {@link ch.alpenflight.persons.web.MemberStatesController} serves the
 * tenant-scoped listitem endpoint at {@code /api/v1/club/member-states}.
 * The local {@code @RestControllerAdvice} translates domain exceptions
 * (PersonNotFoundException, DuplicateClubMembershipException,
 * CrossTenantMembershipBlockedException) to RFC 7807 problem responses —
 * 404, 409, 409 respectively.
 *
 * <p>Per ADR 0023 this package depends on {@code persons.application},
 * {@code persons.domain} (for the exception types), and Spring web. It
 * must NOT depend on {@code persons.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.persons.web;
