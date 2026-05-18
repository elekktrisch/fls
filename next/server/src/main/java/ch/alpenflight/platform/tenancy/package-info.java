/**
 * Multi-tenancy plumbing (ADR 0008). Hibernate's discriminator-based
 * tenancy is driven by {@link ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver};
 * inserts that would write the {@code NO_TENANT} nil-UUID are rejected by
 * {@link ch.alpenflight.platform.tenancy.TenantInsertGuard}. See
 * {@code next/server/CONVENTIONS.md} §Multi-tenancy for the operating rule
 * set (claim precedence, allowlist, native-SQL bypass warning).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.tenancy;
