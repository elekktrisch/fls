# 0014 — Per-tenant theming mechanism

- **Status:** Accepted
- **Date:** 2026-05-15
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  2. Team-familiar stack
  4. Swiss / EU data residency compatible
  5. Structural multi-tenancy supported
  7. Solo-operator operability
  8. Enables fast feature dev post-cutover
  12. Supports the C17 end-user improvements within chosen stack

## Context

The [2026-05-15 vision amendment](../02-vision-and-constraints.md) adds whitelabel branding as constraint C19: per-club nav-bar logo + name, splash photo on landing + public flows, and a primary color (Tailwind CSS variable). Asset storage is already locked as Postgres bytea (vision §2 NFR). The decision still owed is **how the SPA fetches and applies the theme at runtime, with coverage for both authenticated and public flows**.

Public flows (trial-flight, passenger-flight, landing-with-club-context) need branding too — a prospective member arriving from a club's website expecting to see *that club* would be poorly served by a generic FLS shell. That makes the resolution mechanism a two-path problem: authenticated users carry their club via the bearer + tenant resolver ([ADR 0008](.)); public flows carry it via the URL ([S-025 — Tenant-from-URL for public flows](../stories/S-025-public-flow-tenant-from-url.md)).

## Options considered

### Option A — Runtime CSS-variable injection from a branding endpoint
- **Capabilities:** SPA bootstraps with a default theme; after the tenant context resolves (post-auth for logged-in users, post-URL-parse for public flows), it fetches branding JSON `{primaryColor, logoUrl, splashUrl, clubName}` and hydrates CSS custom properties (`--fls-primary`, `--fls-logo-url`, …) on `<html>`. Tailwind v3 reads them via `bg-[var(--fls-primary)]` / `theme.extend.colors`. Two backend endpoints:
  - `GET /api/v1/clubs/myClub/branding` — authenticated, resolves club via bearer + [ADR 0008](.).
  - `GET /api/v1/clubs/by-key/{clubKey}/branding` — public, resolves club from URL-encoded clubKey (URL shape owned by S-025).
  - Both return the same shape; both hit the same Postgres bytea-backed assets via `GET /api/v1/clubs/{id}/logo` / `/splash`.
- **Fit to criteria:** Criterion 2 ✓ (Angular 21 signals make the theme reactive; Tailwind CSS variables are native); criterion 5 ✓ (resolution paths align with the tenant guard mechanism); criterion 7 ✓ (single deployment serves all tenants); criterion 8 ✓ (new club = INSERT row + asset upload, no build pipeline change); criterion 12 ✓ (works cleanly within ADR 0004's Angular 21 commit).
- **Migration cost:** low. Branding endpoint on the server is a thin CRUD; client-side hydration is one effect that runs on tenant-context-change.
- **Ecosystem risk:** low. CSS custom properties have universal browser support; no external dependencies.
- **Escape hatch:** if flash-of-unstyled-content becomes a UX problem, prepend a `<link>` to a per-tenant CSS file (option D); the JSON endpoint can keep working for non-color theming.

### Option B — Build-time theming per club (multi-deployment)
- **Capabilities:** each club gets its own SPA bundle with logo/splash/colors baked in; a reverse proxy routes per-club domain or path to the right bundle.
- **Fit to criteria:** criterion 7 ✗ (multi-deployment ops complexity — per-club builds, per-club certs, per-club deploys), criterion 8 ✗ (every new club triggers a build pipeline change), criterion 5 ✓ (trivially isolated). Violates the solo-operator preference cleanly.
- **Migration cost:** high — CI/CD changes, deployment scripts, per-club domain management.
- **Ecosystem risk:** high — deployment proliferation, certificate management per club.
- **Escape hatch:** collapse to single deployment with option A's runtime injection.

### Option C — Per-tenant CSS file served by backend via `<link>`
- **Capabilities:** server generates a stylesheet per club on demand (`GET /api/v1/clubs/{id}/branding.css`); SPA includes via `<link rel="stylesheet" href="..." />`. Cacheable; minimal client-side logic.
- **Fit to criteria:** criterion 7 ~ (one extra HTTP request per cold load + a CSS-generation endpoint on the backend), criterion 5 ✓, criterion 8 ✓. Workable but adds backend code where option A keeps the logic client-side and reuses an existing JSON-fetching pattern.
- **Migration cost:** low-medium — backend stylesheet generator.
- **Ecosystem risk:** low.
- **Escape hatch:** drop the CSS endpoint; switch to option A.

### Option D — Server-side rendering with per-tenant context
- **Capabilities:** SSR injects the theme into HTML at render time; client hydrates with theme already applied; avoids any flash-of-unstyled-content.
- **Fit to criteria:** criterion 7 ✗ (adds Node-side rendering complexity to a CSR SPA — [ADR 0004](0004-frontend-framework-and-build-tool.md) explicitly opted out of SSR), criterion 8 ✗ (slower dev loop). Fights an existing ADR.
- **Migration cost:** medium-high.
- **Ecosystem risk:** medium.
- **Escape hatch:** drop SSR; revert to A.

## Decision

Chosen: **Option A — Runtime CSS-variable injection from a branding endpoint**, with dual auth/public endpoints (`/api/v1/clubs/myClub/branding` and `/api/v1/clubs/by-key/{clubKey}/branding`). Driven by criterion 7 (solo-operator operability — single deployment, no build-pipeline-per-club) and criterion 12 (works natively with Angular 21 signals + Tailwind v3 CSS variables already locked by ADR 0004). Public-flow club resolution defers to [S-025](../stories/S-025-public-flow-tenant-from-url.md) for the URL shape; the theming ADR consumes whatever S-025 picks. Falls back to a default FLS theme when no club context is resolvable (raw landing page without a club URL parameter).

Build-time theming (B) is rejected as ops-overkill for 12 tenants on a solo-operator setup. The per-tenant CSS file (C) is a viable secondary option but adds backend code where A keeps the surface client-side and aligned with existing fetch patterns. SSR (D) contradicts ADR 0004's CSR-only stance.

## Consequences

- **Positive:**
  - Single SPA deployment serves all clubs; new clubs join via DB INSERT + asset upload, no CI/CD change.
  - Public flows are branded from first render-after-fetch, supporting the C17 whitelabel UX intent on prospective-member touchpoints.
  - Branding is reactive: a club admin updating the primary color in the admin UI triggers a CSS-variable update on next theme refresh without a full reload.
  - Tailwind v3 CSS-variable usage is idiomatic in 2026; the theming mechanism doesn't fight the styling system.

- **Negative:**
  - Flash-of-default-theme on cold load (~100–300 ms): the SPA renders with the default theme until the branding fetch completes. Mitigation: cache the last-seen branding in `localStorage` and apply optimistically on next load.
  - Two backend endpoints to maintain (auth and public). Mitigation: same service method, two controller routes with different `@PreAuthorize` gates.
  - The public branding endpoint is unauthenticated and returns club-level data (name, colors, asset URLs). Acceptable since this is exactly the info a club website would link to externally — but rate-limit the public endpoint to deter scraping.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** branding entity + admin CRUD UI — `Club.branding` with `primaryColor` (varchar), `logoBlob` (bytea), `splashBlob` (bytea); admin UI to upload + preview. Land under E-04 (master data) or a new whitelabel mini-epic.
  - **Story:** branding fetch + CSS-variable hydration in the SPA — Angular effect that runs on tenant-context-change (signal). Land under E-01 (foundations) or the new whitelabel mini-epic.
  - **Story:** public branding endpoint (`/by-key/{clubKey}/branding`) + rate-limit policy.
  - **Story:** S-025 (already in backlog) chooses the public-flow URL shape; theming consumes it. No re-decision here.
  - **Story:** `localStorage` optimistic-branding-cache for cold-load FOUC mitigation. Optional; defer if FOUC turns out to be acceptable.
  - **NFR addition (covered by vision amendment §2):** asset size caps — operator decides during the upload-UI story; suggested defaults logo ≤ 200 KB, splash ≤ 2 MB.
