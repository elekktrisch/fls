---
id: J-11
title: Email templates + Articles masterdata (+ gallery simplification)
epic: E-06
status: in_progress
started_at: 2026-06-22
journey0: false
carved: true
depends_on: [J-0]
rolls_up: [S-055]
acceptance:
  - "[happy] A CLUB_ADMINISTRATOR opens /email-templates and sees the system-default templates UNIONed with any own-club override rows; editing a system template clones it to a per-club override (IsSystemTemplate=false, club_id=caller) that renders at send time WITHOUT redeploy."
  - "[happy] 'Reset to default' removes the club override row so the system/file default renders again."
  - "[edge] Tenancy: a club admin sees system templates + ONLY own-club overrides (never another club's); a non-admin cannot mutate (403)."
  - "[happy] Articles: the shipped /articles list+edit screen (S-054) round-trips create/edit/soft-delete tenant-scoped; includeInactive surfaces inactive — verified against the current UI kit, not rebuilt."
  - "[migration/parity] Article: the existing ArticleMapper round-trips real legacy articles into AlpenFlight — green fan-out parity (the migration done-bar J-10b's DeliveryItem.article_id RESTRICT FK needs)."
  - "[debt] GALLERY-SIMPLIFY: the proof gallery is ONE stable-bookmark page rendering only the in-flight journey; the all-journeys index + history pages + sub-path split are deleted; one deploy + the deployed-link-check survive; the DEPLOYED bookmark + assets resolve 200."
screen: /email-templates (new) + /articles (verify the shipped S-054 screen); plus the proof-gallery rewrite (infra)
headless_pulled_in: Thymeleaf DB-override-then-file-fallback resolver chain (consumes S-082) — homed by the /email-templates screen
migration: Article (fanout-prove the existing S-054 ArticleMapper — the J-10b ARTICLE-FK done-bar); EmailTemplate N/A unless the legacy seed carries club-override rows (verify at ship)
parity_test: alpenflight/web/e2e/tests/real-idp/email-templates.spec.ts (new; greenfield UI — no legacy pairing) + the Article fanout-parity block
mock_test: e2e/tests/masterdata/(email-templates)   # per-push mock-e2e runs ONLY this journey's own spec; the regex stem fail-safes to the full suite until the screen's spec lands, then auto-scopes — prior journeys' masterdata mock specs (articles/aircraft/flight-types/locations) stay excluded
adr_refs: [0005, 0008, 0013, 0022, 0027]
---

## Context

Email templates are the last club-customizable masterdata: a club admin overrides the transactional-email
defaults (invoice, planning, registration) per-club without a redeploy. Legacy stores the override as an
`EmailTemplate` row whose `ClubId`-null/`IsSystemTemplate` rows are the global defaults; a club override is
a clone with the caller's `ClubId`. AlpenFlight ships the defaults as Thymeleaf files (S-082) but has **no
EmailTemplate entity, schema, screen, or override mechanism** — this journey builds that greenfield. Articles
already shipped (S-054), so its half is verify-the-deployed-screen + **fanout-prove the existing mapper** —
the Article migration done-bar that J-10b's `DeliveryItem.article_id` RESTRICT FK depends on. Per the
debt-burndown final lap (operator 2026-06-22), this journey also clears **GALLERY-SIMPLIFY** as its dominant
tech-debt slot; after J-11, do-plan reverts to 60/40 and the burndown marker is deleted.

## Spec must assert

Grounded in legacy `TemplateService.cs:105-130` (union read) + `:242` (clone-on-customize).

1. **Union read** — `/email-templates` returns system/default templates + the caller's own-club overrides;
   never another club's. SystemAdmin vs ClubAdmin visibility per `TemplateService.cs:105-130`.
2. **Clone-on-customize** — editing a system template creates a per-club override row
   (`IsSystemTemplate=false`, `club_id=caller`); it does NOT mutate the system default.
3. **Override-at-send** — the Thymeleaf resolver chain prefers the DB override row, falling back to the
   file default when no override exists (consumes S-082); no redeploy needed.
4. **Reset** — removing the override row restores the system/file default.
5. **Articles** — the shipped `/articles` screen round-trips tenant-scoped CRUD + soft-delete +
   includeInactive (verify, don't rebuild); the `ArticleMapper` is green in the real fanout.
6. **Gallery** — one page, current journey only; the deployed bookmark + every asset resolve 200.

## Decisions (carve-time)

- **Articles is already shipped (S-054, merged).** J-11 does NOT rebuild it — it verifies the deployed
  list/edit screens against the current UI kit and fanout-proves the existing `ArticleMapper`
  (`migration-bundle/.../accounting/ArticleMapper.java`, binds tenant from legacy `ClubId`). Article is
  `@TenantId operating_club_id`, a direct column (no FK to other-journey entities, no tenancy pivot).
- **EmailTemplate is greenfield** — new aggregate + `t_email_template` schema (`@TenantId club_id`
  **nullable**: null/system rows are global defaults; structural invariants only, ADR 0022 §2) + REST +
  Thymeleaf resolver + SPA screen. `LanguageId` is a plain int/enum, NOT an FK aggregate (Language is
  migrated by no journey). Tenancy is **system+club UNION**, not a flat club filter.
- **EmailTemplate migration = N/A initially** — the defaults ship as Thymeleaf files; only club-override
  rows would migrate and the seed likely has none. Verify at ship: if the legacy seed carries club
  `EmailTemplate` rows, add a mapper (migration-test-data-isolation — assert what the seed genuinely
  produces); else record N/A. Not in `UnmappedTables.java` today.
- **No design reference, no legacy web screen** for either screen (`docs/modernization/design-reference/`
  has none; legacy email templates are admin/server-managed, articles are a service with no flsweb screen).
  Design /email-templates from the AlpenFlight UI kit; its proof is the real-idp CRUD + override-at-send
  video, NOT a legacy↔AlpenFlight pairing.
- **Roadmap erratum:** the `_ORDER.md` row lists `rolls_up: S-055, S-158, S-177`. S-158 (branding-preview
  molecule) + S-177 (club join-code) are NOT articles/email — they belong to J-16/J-12. J-11 rolls up
  **only S-055** (S-054 already implemented).
- **Enlarged journey (operator-sanctioned 2026-06-22).** Full feature (EmailTemplate + Articles verify) +
  GALLERY-SIMPLIFY in ONE journey. `/do-ship` MUST hold the manager-context-budget rule hard — delegate
  every token-heavy read (legacy, gate logs, the gallery YAML, diffs) to sub-agents; that lean+delegate
  discipline is what makes both halves fit one journey.

## Notes — riders to fold (per /do-ship §2)

- **GALLERY-SIMPLIFY** (the dominant ≤70% debt slot, operator-targeted — the bookmark pain). Collapse the
  proof gallery to ONE stable-bookmark page rendering only the in-flight journey; delete the all-journeys
  index (`generate-previews-index.mjs`), the per-merged-journey history pages, the per-push/fanout/
  legacy-parity sub-path split, the per-journey staging blocks + multi-context `producedBy` logic; keep one
  deploy + the deployed-link-check (CDN slack). Verify the DEPLOYED bookmark, not the unit test. SUPERSEDES
  every prior gallery rider. *(seam: `generate-gallery.mjs` + delete `generate-previews-index.mjs` +
  `proof-gallery-links.spec.ts` + `expected-shots.json` + the gallery deploy/staging steps across `ci.yml`
  + `alpenflight-proof-fanout.yml`)* — **after this journey, revert do-plan to 60/40 + delete the marker.**
- **INTERNAL-AFFORDANCE-ARCHGUARD** (server-touch — J-11 adds the EmailTemplate server): the ArchUnit guard
  asserting every `/api/v1/internal/` controller carries `@Profile`(non-prod) + `@Hidden` (ADR 0029).
- **COMMENT-STRIP** (per-touch on the new EmailTemplate server/web/e2e files + the gallery files J-11 edits).
- **WORKFLOW-SLIM** (partial overlap): GALLERY-SIMPLIFY removes the gallery staging blocks; the remaining
  composite-action extraction + real-idp shard + KC-26 quarantine are large — fold what fits the budget,
  defer the rest.
- **HELPER-PRUNE-CREDIT** (only if J-11 touches the accounting e2e specs): delete the 2 `@helper`
  credit-edge cases in `delivery-creation-test-parity.spec.ts` (covered-by FlightTimeStageTest +
  AccountingDeliveryEngineCreditIT).

## Notes — likely seams (non-binding, for /do-ship)

- **EmailTemplate aggregate + schema** — one aggregate (`EmailTemplate` + nullable-club tenancy, system
  default vs override) + Flyway `t_email_template`.
- **EmailTemplate REST + service** — `/api/v1/email-templates/**` (CLUB_ADMINISTRATOR write, system+club
  union read, clone-on-customize) + audit.
- **Thymeleaf resolver chain** — DB-override-then-file-fallback (consumes S-082).
- **EmailTemplate SPA screen** — store + list/edit route/component + template-source editor + reset-to-default.
- **Articles verify** — confirm the deployed `/articles` screen + fanout-prove `ArticleMapper` (1 task).
- **GALLERY-SIMPLIFY** — its own cluster (generator rewrite / deletes / CI+fanout deploy rewire), sized per
  the gate.

## Oracle decisions (do-ship, from the legacy-oracle 2026-06-22)

EmailTemplate legacy source: `TemplateService.cs` (`:116-133` union read, `:238-251` clone-on-customize/upsert,
`:151-166` send-time override-then-default, `:271-288` reset=hard-delete override), `EmailTemplate.cs` entity,
`EmailTemplatesController.cs` (`:15` read=ClubAdmin|SystemAdmin, `:75` create=SystemAdmin-only). AlpenFlight is
**greenfield** — these are the resolver/CRUD semantics to mirror, not a parity test.

- **AlpenFlight DB holds ONLY club overrides** (`club_id NOT NULL`); system defaults are the S-082 Thymeleaf
  FILES. So the union read is **files ∪ db-override-rows**, not a nullable-club table — cleaner `@TenantId` (no
  null-tenant rows), and `UNIQUE(club_id, template_key, language_locale)` is clean. (Supersedes the carve's
  "nullable club_id" note — only needed if system rows lived in the DB; they don't.)
- **Override row stores** the customizable fields: `template_key` (the transactional-email selector — e.g.
  `lostpassword`/`planningday-ok`), `language_locale`, `subject`, `body` (Thymeleaf source — greenfield, NOT the
  legacy Velocity), optionally from/replyTo. Identity = `(club_id, template_key, language_locale)`.
- **Clone-on-customize** = upsert the override row (insert if none, update-in-place if one exists); never mutates
  a file default. **Reset** = delete the override row → resolver falls back to the file. **Authz**: write =
  `CLUB_ADMINISTRATOR` (own club only), non-admin = 403.
- **Three legacy quirks built greenfield-correct (NOT reproduced, NOT escalations):** (1) legacy drops `clubId`
  on 3 senders (flightreport/licenceexpiressoon/aircraftstatisticreport) so their overrides never apply — the
  AlpenFlight resolver applies overrides uniformly by `(tenant, key, locale)`; those senders aren't built here.
  (2) legacy dedups case-sensitively in the list but `ToUpper` at send — AlpenFlight canonicalizes the key
  case-insensitively everywhere. (3) legacy has no uniqueness invariant — AlpenFlight adds the UNIQUE above
  (no migration dup risk: EmailTemplate migration is N/A).
- **EmailTemplate migration = N/A (confirmed).** All legacy seed rows are `ClubId=NULL, IsSystemTemplate=1`
  (pure system defaults → AlpenFlight Thymeleaf files). No club-override rows in the seed/fanout export → no
  mapper. **Article is the journey's only mapper** → the `fan-out parity` job remains a HARD gate (Article).

## Tasks

- [x] **T-01** — Real-idp `email-templates.spec.ts` stub (structure + selectors + thin happy-path flow) + scaffold the J-11 proof-gallery page + link from the persistent index.
- [x] **T-02** — Scope the per-push gate to J-11 (journey `mock_test`/`real_test` frontmatter + CI filter); prior journeys run mock-IdP.
- [x] **T-03** — EmailTemplate aggregate + Flyway `t_email_template` (club_id NOT-NULL `@TenantId`, `UNIQUE(club_id,template_key,language_locale)`, structural invariants only; domain customize-upsert + reset methods per ADR 0022 §2).
- [ ] **T-04** — EmailTemplate REST `/api/v1/email-templates/**` + application service: union read (files ∪ overrides), clone-on-customize upsert, reset-delete, `CLUB_ADMINISTRATOR` write / 403 non-admin, audit.
- [ ] **T-05** — Thymeleaf DB-override-then-file-fallback resolver chain (custom `ITemplateResolver` keyed `(tenant,key,locale)` ahead of the file resolver; consumes S-082).
- [ ] **T-06** — EmailTemplate SPA store + API client (list/get/save/reset over `/api/v1/email-templates`).
- [ ] **T-07** — EmailTemplate SPA screen: route + list/edit component + template-source editor + reset-to-default; nav entry + role visibility (chrome-reachable); per-touch COMMENT-STRIP of `nav-sections.ts`/`app.routes.ts`.
- [ ] **T-08** — Article migration: bind/verify `ArticleMapper` scoped by legacy `ClubId` tenant + a real-producer collision/orphan round-trip IT (reds in `check`); per-touch COMMENT-STRIP of `MapperLegacyBindings.java`. (The J-10b `DeliveryItem.article_id` RESTRICT done-bar.)
- [ ] **T-09** — GALLERY-SIMPLIFY (a): rewrite `generate-gallery.mjs` to ONE current-journey-only page; delete `generate-previews-index.mjs` + `proof-gallery-links.spec.ts` (replace w/ the one-page deployed-link-check) + `expected-shots.json`.
- [ ] **T-10** — GALLERY-SIMPLIFY (b): collapse the gallery deploy/staging/sub-path steps across `ci.yml` + `alpenflight-proof-fanout.yml` to one deploy + one deployed-link-check (CDN slack).
- [ ] **T-11** — WORKFLOW-SLIM (partial): quarantine the 3 KC-26 specs + shard the §4 cross-journey real-idp regression (keep coverage, beat the step timeout). Composite-action YAML cut deferred (note for next).
- [ ] **T-12** — INTERNAL-AFFORDANCE-ARCHGUARD: ArchUnit guard asserting every `/api/v1/internal/` controller carries `@Profile`(non-prod) + `@Hidden` (ADR 0029).
- [ ] **T-13** — HELPER-PRUNE + HELPER-PRUNE-CREDIT: re-confirm the backend twins green, then delete the 5 redundant `@helper` e2e cases (3 in `validation-hardening.spec.ts`, 2 in `delivery-creation-test-parity.spec.ts`).
- [ ] **T-14** — Thicken `email-templates.spec.ts` to full real assertions (union read / clone-on-customize / override-at-send / reset) + add the Articles screen verify (CRUD + soft-delete + includeInactive over the deployed `/articles`).
