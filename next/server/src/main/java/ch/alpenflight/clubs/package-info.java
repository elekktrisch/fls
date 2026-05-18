/**
 * Clubs aggregate — the tenant root itself. Not @TenantId-annotated.
 *
 * <p>S-048 lands this as the first end-to-end vertical slice (REST → service
 * → JPA → Postgres) with mocked authorization (profile {@code mock-auth},
 * see {@code ch.alpenflight.auth}). The {@code @PreAuthorize} predicates
 * stay across the S-019/S-020 auth-chain swap; only the principal source
 * flips from {@code MockSecurityConfig} to the real JWT decoder.
 *
 * <p>Authorization is by role, never by tenant filter — Clubs are the
 * tenant boundary. {@code ix_club_country} / {@code ix_club_state} indexes
 * live in V2; this package never queries those columns directly.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs;
