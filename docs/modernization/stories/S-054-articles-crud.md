---
id: S-054
title: Articles CRUD
epic: E-06
status: in_progress
started_at: 2026-05-24
github_issue: 110
github_pr: 111
depends_on: [S-048]
acceptance:
  - `Article` entity ported (per-club, tenant-scoped via `operating_club_id` `@TenantId`). Referenced by `DeliveryItem.article_id` — pre-req for E-09.
  - REST surface at `/api/v1/articles/**` — `CLUB_ADMINISTRATOR`-gated per S-159 (no `SYSTEM_ADMINISTRATOR` co-allowance, no `/admin/` variant). Soft-delete only.
  - List + edit screens. List defaults to `is_active = true`; `?includeInactive=true` surfaces deactivated rows.
  - `@AuditedTarget("article")` on the aggregate; S-027 listener emits mutations (no bespoke audit code).
  - New Playwright spec at `alpenflight/web/e2e/tests/masterdata/articles-crud.spec.ts` (greenfield — no legacy oracle).
estimate: S
adr_refs: [0005, 0008, 0022, 0023]
parity_test: alpenflight/web/e2e/tests/masterdata/articles-crud.spec.ts
parity_excluded:
  - Legacy `Club.LastArticleSynchronisationOn` + `GET/PUT /api/v1/articles/lastSync` endpoint pair (`ArticleService.cs:161-197`) — external Proffix-sync timestamp; **deferred** until E-09 consumer materialises.
  - Legacy paged search `POST /page/{pageStart}/{pageSize}` (`ArticleOverviewSearchFilter`) — **excluded**; full-list-per-club is fine at expected cardinality.
  - Legacy `CanUpdateRecord` / `CanDeleteRecord` per-row DTO security flags (`ArticleService.cs:218-223`) — replaced by server-side `@PreAuthorize` + SPA capability signals from S-026.
  - Legacy physical `DELETE` (`ArticleService.cs:144`) — replaced by soft-delete; `delivery_item.article_id` FK RESTRICT + invoice integrity (Swiss OR Art. 957a) require it.
refined: true
refined_at: 2026-05-24
refined_specialists: [requirements, solution, qa, security]
---

## Context
No e2e spec exists for articles in legacy (per current-state §2). This story adds the surface; depth coverage comes via E-13. `article` table already in V3 (`V3__flights_aircraft_locations.sql:476-496`) — no new Flyway migration. Don't conflate Article with AccountingRuleFilter — Article is a price-list row (article number, name); AccountingRuleFilter selects an Article to produce a DeliveryItem.

## Acceptance criteria
See frontmatter.

## Tasks
Superseded by acceptance criteria.

<!-- modernize-refine: start -->

## Design notes

### Module layout
Mirror S-053's `flighttypes/{domain,application,infra,web}/` template under `alpenflight/server/src/main/java/ch/alpenflight/articles/`.

### Tenancy & authorization
`Article.operatingClubId` is the `@TenantId` discriminator. HTTP **writes** are `CLUB_ADMINISTRATOR`-only per S-159 (no `SYSTEM_ADMINISTRATOR` co-allowance, no `/admin/` variant). Read scope: see Open design questions.

### Identity & uniqueness
`(operating_club_id, article_number)` is identity-bearing (partial unique index already in V3, scoped `WHERE deleted_on IS NULL`). Duplicate insert/update against a **live** row → `DuplicateArticleNumberException` → HTTP 409 (mirror S-053's `DuplicateFlightTypeCodeException`). Number reuse after soft-delete is **structurally allowed** by the partial unique and is intended (retire `A-100`, later create a new `A-100`); aggregate `register()` treats soft-deleted rows as absent for collision checks.

### Delete semantics
Soft-delete only (`deleted_on = now()`, `deleted_by_user_id`). FK RESTRICT from `delivery_item.article_id` is structural protection against accidental physical DELETE — never trips on the CRUD path. DELETE returns 204 unconditionally; referenced articles remain resolvable to E-09 via the frozen `delivery_item.article_number` snapshot.

### REST surface
Standard CRUD at `/api/v1/articles` (list, GET, POST, PUT, DELETE). No paged-search variant. List query: `?isActive=true|false` defaulting to `true` (matches legacy `ArticleService:70-72` filter + E-09 rules-engine expectation that inactive articles are never picked).

### Aggregate behavior (ADR 0022 §2)
Domain methods on `Article`: `rename`, `updateInfo`, `updateDescription`, `activate` / `deactivate` (idempotent; cannot mutate a soft-deleted article), `softDelete` (idempotent). `article_number` format validation (non-blank, trimmed, length ≤ 50, no leading/trailing whitespace) lives on the aggregate constructor. **No schema CHECK constraints** — structural NOT NULL + partial UNIQUE already in V3 are the only DB-level rules. Whether `article_number` is mutable post-create → Open design questions.

### Audit
`@AuditedTarget("article")` on the aggregate; S-027 `MutationAuditEventListener` handles emission. No PII → no redaction.

### Frontend
Mirror S-053's feature module — Signal Store with `withEntities`, list + edit screens using S-008 ng-zorro + Tailwind primitives. Generated TS client via orval. Capability gates via S-026 role signal (`CLUB_ADMINISTRATOR`).

### Field semantics
`article_info` is the short single-line annotation (VARCHAR(250), shown in list/picker secondary text); `description` is the long-form body (TEXT, shown only on edit/detail). No markdown rendering in this story. Both nullable.

### Integration with other stories
**Consumes:** V3 `article` table; S-027 audit infrastructure; S-008 UI kit; S-004 orval; S-019/S-020/S-022/S-026 real auth chain (replacing S-048's mock).
**Produces:** `Article` aggregate + `ArticleResponse` DTO — consumed by **E-09 DeliveryItem** (FK `delivery_item.article_id` + frozen `article_number` snapshot per Swiss OR Art. 957a). Also unblocks the legacy `lastSync` sync endpoint port whenever the E-09 consumer arrives.

## Edge cases & hidden requirements

### Delete semantics + number reuse
- Legacy `ArticleService.DeleteArticle` (`flsserver/src/FLS.Server.Service/ArticleService.cs:144`) does a hard `Remove`; do **NOT** port. Soft-delete is the only path now.
- `delete()` is idempotent — second call on an already-soft-deleted article is a no-op, `deletedOn` unchanged.
- Reusing a soft-deleted `article_number` for a new live article is intended (see Design notes); aggregate must not block it at service level.

### `is_active` is distinct from soft-delete
Visibility/seasonality toggle, editable on the edit form; default `true` on create. Soft-deleted articles are **never** returned regardless of `?includeInactive` — that flag is for `is_active=false` rows only.

### Soft-delete unique-index trap
The partial unique `WHERE deleted_on IS NULL` means JPA queries against `article` MUST filter on `deletedOn IS NULL` by default (`@Where` annotation or repository default scope) — otherwise list views surface tombstones. Mirror S-053's repository pattern.

### Frontend / DTO shape
- DTOs are Java records; Bean Validation on the wire + aggregate re-validates per ADR 0022 §2.
- No `CanUpdateRecord`/`CanDeleteRecord` DTO flags — capability checks happen server-side (`@PreAuthorize`) and client-side via S-026's role signal.

### OpenAPI / orval regeneration
Adding `ArticlesController` shifts the spec; rerun `./gradlew generateOpenApiSnapshot` and commit the refreshed snapshot in the same PR (otherwise `next-build` CI fails on the snapshot diff). orval-generated `ArticlesService` lands under `next/web/src/app/api/generated/articles/`.

## Security plan

### Authorization
- Writes: `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")` on POST/PUT/DELETE per S-159. **NO** `SYSTEM_ADMINISTRATOR` disjunction.
- Tenant gate: auto via `@TenantId` on `operating_club_id`. `GET /api/v1/articles/{id}` for a foreign-tenant id returns **404** (Hibernate filter returns empty rows) — same 404-not-403 invariant as S-053.
- Read scope (CLUB_ADMINISTRATOR-only vs widened to any authenticated tenant principal): see Open design questions.

### Input validation
- Bean Validation: `@NotBlank @Size(max=50)` on `articleNumber`; `@NotBlank @Size(max=250)` on `articleName`; `@Size(max=250)` on `articleInfo`. **No regex** on `articleNumber` — legacy carries business-meaningful codes (Proffix-style); length + non-blank only.
- Aggregate re-validates `articleNumber` in constructor.

### Cross-tenant leakage
Covered by the S-024 per-repository CI sweep — `Article` picked up automatically. Nothing bespoke.

### Audit + PII
`@AuditedTarget("article")`. No PII at the Article level — free to log at INFO; no redaction.

### Soft-delete + invoice integrity
`delivery_item.article_number` is a frozen snapshot at booking (Swiss OR Art. 957a); never re-resolved from `article_id`. Renaming or soft-deleting an article does not corrupt historical invoices. The implementer does NOT need a service-layer block on "is this article referenced?" — the snapshot column makes the operation safe.

### OWASP
A01 Broken Access Control — `@PreAuthorize` + `@TenantId` (above). Other rows N/A or framework-covered.

## Test plan

### Pyramid
~6 unit on `Article` aggregate / 1 controller IT (testcontainer + real Postgres + real Security) covering CRUD + tenant + audit / 1 Playwright happy round-trip.

### Domain unit (`Article` aggregate)
- `articleNumber` non-blank + length + trim validation; reject blank `articleName`.
- Soft-delete idempotence; cannot mutate a soft-deleted article.
- `activate()` / `deactivate()` flip `isActive` and are idempotent.

### Controller IT (`ArticlesControllerIT`, mirror S-053's `FlightTypesControllerIT`)
- Tenant isolation: any verb on a foreign-tenant id → 404 (the 404-not-403 invariant).
- POST duplicate `(operatingClubId, articleNumber)` where prior is live → 409; where prior is soft-deleted → 201 (number reuse).
- DELETE → 204; subsequent GET → 404; repo escape hatch confirms row physically present with `deletedOn` set.
- `?includeInactive=true` surfaces `is_active=false` rows; default omits them; never surfaces soft-deleted rows.
- Audit: one `MutationAuditEvent` asserted per insert/update/delete verb (listener already covered upstream).
- Authz: non-CLUB_ADMIN write → 403. Read assertion depends on the Open design question resolution.

### Playwright (`articles-crud.spec.ts`, runs against `next` profile)
One happy round-trip: club admin logs in, creates → edits → soft-deletes an article; list reflects each step.

### Parity
`parity_test: none` per AC; new Playwright spec IS the contract. Parity exclusions documented in frontmatter.

### Fixtures / CI guards
Reuse `PostgresTestcontainer` + `TenantContextExtension` from S-053; regenerate OpenAPI snapshot in the same PR.

## Performance plan
(N/A — small masterdata CRUD; identical performance shape to S-053. Per-club article cardinality is small (dozens-to-low-hundreds); no pagination, no hot path, no caching needed.)

## Open design questions

1. **`article_number` mutability post-create.** Architect proposes immutable (dedicated `renumber()` gated on no `delivery_item` references). Requirements-engineer notes it is **safe** to keep mutable because `delivery_item.article_number` is a frozen snapshot per Swiss OR Art. 957a — historical invoices unaffected. Legacy permits update (`ArticleService.UpdateArticleDetails` copies all fields). **Recommended:** keep mutable on PUT (matches legacy, snapshot makes it safe, simpler aggregate); collision check on the new value among live rows applies.
2. **Read-scope widening.** Writes are CLUB_ADMINISTRATOR-only per S-159. Should `GET /api/v1/articles` + `GET /{id}` be widened to **any authenticated tenant principal** (members consulting the price list; S-058 Flight booking flow displaying article info)? S-053 widened `GET /flight-cost-balance-types` because it is reference data; Article is tenant-scoped masterdata, so the parallel is not automatic. **Recommended:** widen reads to any authenticated principal in the tenant (UX-driven; non-admin members will surface articles in the booking/delivery flows S-058+). If widened, write endpoints stay on `ArticlesController` with `@PreAuthorize` at method level rather than splitting into a separate admin controller (single-class is fine — path-confusion guard from S-053 FCBT is unnecessary here because the path is shared).

<!-- modernize-refine: end -->

## Notes
Don't conflate Article with AccountingRuleFilter — Article is a price-list row (article number, name); the rules engine picks an Article to produce a DeliveryItem.
