---
id: S-054
title: Articles CRUD
epic: E-06
status: done
started_at: 2026-05-24
done_at: 2026-05-24
github_issue: 110
github_pr: 111
depends_on: [S-048]
acceptance:
  - `Article` entity ported (per-club, tenant-scoped via `operating_club_id` `@TenantId`). Referenced by `DeliveryItem.article_id` — pre-req for E-09.
  - REST surface at `/api/v1/articles/**` — `CLUB_ADMINISTRATOR`-gated writes per S-159 (no `SYSTEM_ADMINISTRATOR` co-allowance, no `/admin/` variant); reads open to any authenticated tenant principal. Soft-delete only.
  - List + edit screens. List defaults to `is_active = true`; `?includeInactive=true` surfaces deactivated rows.
  - Mutations recorded via `AuditTrail.record(AuditAction.{CREATE,UPDATE,DELETE}, AuditedTarget...)` in `ArticlesService` (mirror S-053 FlightType); `entityType="Article"`.
  - New Playwright spec at `alpenflight/web/e2e/tests/masterdata/articles-crud.spec.ts` (greenfield — no legacy oracle).
estimate: S
adr_refs: [0005, 0008, 0022, 0023]
parity_test: alpenflight/web/e2e/tests/masterdata/articles-crud.spec.ts
parity_excluded:
  - Legacy `Club.LastArticleSynchronisationOn` + `GET/PUT /api/v1/articles/lastSync` endpoint pair (`flsserver/src/FLS.Server.Service/ArticleService.cs:161-197`) — external Proffix-sync timestamp; **deferred** until E-09 consumer materialises.
  - Legacy paged search `POST /page/{pageStart}/{pageSize}` (`ArticleOverviewSearchFilter`) — **excluded**; full-list-per-club is fine at expected cardinality.
  - Legacy `CanUpdateRecord` / `CanDeleteRecord` per-row DTO security flags (`ArticleService.cs:218-223`) — replaced by server-side `@PreAuthorize` + SPA capability signals from S-026.
  - Legacy physical `DELETE` (`ArticleService.cs:144`) — replaced by soft-delete; invoice integrity (Swiss OR Art. 957a) requires the `delivery_item.article_number` snapshot to outlive the article row.
  - Legacy default list returns active + inactive rows (`ArticleService.cs:30-47`) — new default hides inactive; `?includeInactive=true` restores the legacy union for operator catalogue management.
refined: true
refined_at: 2026-05-24
refined_specialists: [requirements, solution, qa, security]
merged: true
merged_at: 2026-05-24
---

## Context
`article` table already in V3 (`V3__flights_aircraft_locations.sql:476-496`) — no new Flyway migration. Don't conflate Article with AccountingRuleFilter: Article is a price-list row (article number + name + info + description); AccountingRuleFilter selects an Article to produce a DeliveryItem.

## Cross-story contracts

- **Consumes** the V3 `article` table, S-027 audit infrastructure, S-008 UI kit, S-004 orval pipeline, and the S-019..S-026 real-auth chain (replacing S-048's mock).
- **Produces** the `Article` aggregate + `ArticleResponse` DTO consumed by **E-09 DeliveryItem** (FK `delivery_item.article_id` + frozen `article_number` snapshot per Swiss OR Art. 957a). Also unblocks the legacy `lastSync` endpoint port whenever the E-09 consumer arrives.

## Resolved design decisions

- **`article_number` is mutable post-create.** `delivery_item.article_number` is a frozen snapshot at booking, so renaming an Article never corrupts historical invoices. PUT collision check applies against live rows only; reuse after soft-delete is intentional and supported by the V3 partial UNIQUE (`WHERE deleted_on IS NULL`).
- **Reads widened to any authenticated tenant principal.** Writes stay `CLUB_ADMINISTRATOR`-only per S-159; reads served by the same controller (no admin/read split — the path is shared, no path-confusion risk).
- **`is_active` is a catalogue-visibility toggle distinct from soft-delete.** List defaults to `is_active=true`; `?includeInactive=true` surfaces deactivated rows; soft-deleted rows are NEVER surfaced regardless of the flag.

## Boyscout (this PR)

- New `ch.alpenflight.platform.persistence.PersistedAuditActor` annotation bundles `@SuppressWarnings({"UnusedVariable","FieldCanBeLocal"})` + the WHY for write-only audit-actor columns. Applied to `Article.deletedByUserId` and `FlightType.deletedByUserId`.

## Follow-ups (carried)

- Soft-delete actor-null fail-fast (security-reviewer improvement on this story + S-053): if `userLookup.resolveUserIdFor(jwt)` returns empty on a mutating endpoint, the audit row's `deleted_by_user_id` is silently null. Carry as a dedicated story before more soft-deletable aggregates land.
- The full sweep of "drop `@Column(name=...)` in favour of SpringPhysicalNamingStrategy default naming" was applied only to `Article` (in-scope new code). FlightType / Aircraft / Person / Club still carry the explicit overrides; codify the convention as an ArchUnit rule before a wide sweep.
