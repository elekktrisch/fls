/**
 * Multi-tenancy plumbing (ADR 0008). Hibernate's discriminator-based
 * tenancy is driven by {@link ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver};
 * inserts whose {@code club_id} resolves to the {@code NO_TENANT} nil UUID
 * are rejected by the {@code fk_<table>_club_id} foreign-key constraint
 * (the nil UUID is not present in {@code club}). See
 * {@code next/server/CONVENTIONS.md} §Multi-tenancy for the operating rule
 * set (claim precedence, native-SQL bypass warning).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.tenancy;
