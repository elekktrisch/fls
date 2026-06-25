/**
 * Articles module — per-club catalogue of billable articles (article number,
 * name, info, description, active flag). The {@code Article} aggregate root
 * is tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@code operatingClubId}; reads + writes filter to the caller's tenant
 * structurally, never by convention.
 *
 * <p>Identity-bearing partial UNIQUE on
 * {@code (operating_club_id, article_number) WHERE deleted_on IS NULL} (V3)
 * lets a tenant soft-delete and recreate the same number — same shape as
 * S-053 FlightType and S-050 Aircraft. Renaming an {@code article_number}
 * post-create is safe: {@code delivery_item.article_number} is a frozen
 * snapshot at booking (Swiss OR Art. 957a), never re-resolved from
 * {@code article_id}.
 *
 * <p>Layered per ADR 0023 into four sub-packages: {@code domain} (aggregate
 * + repository port + domain exceptions), {@code application} (orchestration
 * service + DTOs + mapper), {@code web} (REST controller + exception
 * handler), {@code infra} (Spring Data JPA adapter).
 *
 * <p>Authz model (S-159): writes gated to CLUB_ADMINISTRATOR; reads open to
 * any authenticated principal in the tenant (the price-list picker on
 * future Flight / DeliveryItem flows reads articles without an elevated
 * role). Cross-tenant detail reads surface as 404 — the row is invisible
 * under the caller's tenant scope, not 403. SYSTEM_ADMINISTRATOR has no
 * rights here.
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module (matching {@code aircraft} / {@code flighttypes} /
 * {@code clubs} / {@code referencedata}) so the accounting delivery-create engine
 * may resolve a frozen {@code article_number} back to its {@code article_id}
 * through {@link ch.alpenflight.articles.domain.ArticleRepository} when persisting
 * a Delivery's line items.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.articles;
