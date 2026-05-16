---
id: S-128
title: Technical rebrand FLS → AlpenFlight (packages, configs, docs)
epic: E-14
status: done
started_at: 2026-05-16
done_at: 2026-05-16
estimate: M
parity_test: none
depends_on: []
adr_refs: []
refined: true
refined_at: 2026-05-16
refined_specialists: [requirements, solution, qa]
github_issue: 30
github_pr: 31
---

## Context

The rewrite drops the "FLS" / "Flight Logging System" name in favor of **AlpenFlight** (canonical domain `alpenflight.ch`). Decided 2026-05-16 by the operator after a /grill-me round on names, domain availability, and DACH-class trademark risk. No new ADR is filed for this — the decision lives in this story plus operator memory. If it needs canonical status later it can be promoted to an ADR at zero cost.

This story covers the **technical surfaces inside `next/` plus the modernization docs**. It deliberately excludes:

- User-facing branding (UI labels, login screen, email-from address, customer-facing names) — those land closer to cutover.
- Domain registration of `alpenflight.ch` — operator task, not engineering.
- Trademark filing — deferred (no Markenanwalt budget).
- Repo / GitHub-org rename — separate cutover-band concern.
- Legacy code under `flsserver/` and `flsweb/` — out of scope per top-level `CLAUDE.md` ("Legacy is reference-only").

Sibling story **S-120** ("Product slug + `next/` → final-folder-slug rename") covers the *folder*-slug change. The two are independent: Java packages live under `next/server/src/.../`, so the parent folder slug (`next/`) and the package slug (`ch.fls`) can be renamed in either order. They could also be unified into a single execution at refinement time; flagging for operator decision.

## Acceptance criteria

### Server (Java / Gradle / Spring)

- [ ] Java packages `ch.fls.*` renamed to `ch.alpenflight.*` across `next/server/src/main/java/`, `next/server/src/test/java/`, and `next/server/src/nullawayDemo/java/`. IDE-refactor preferred so imports update cleanly.
- [ ] `next/server/build.gradle.kts` — `group = "ch.fls"` → `group = "ch.alpenflight"`.
- [ ] `next/server/settings.gradle.kts` — `rootProject.name = "fls-server"` → `"alpenflight-server"`.
- [ ] `next/server/src/main/resources/application*.yml` — `spring.application.name: fls-server` → `alpenflight-server`.
- [ ] All log-prefix strings (e.g. `[fls-server]` in `SharedPostgresContainer.java`) updated.
- [ ] Env-var prefix decided and applied. Currently only `FLS_TEST_ROOT` is in use, so the migration is cheap. Propose `ALPENFLIGHT_*` (verbose, self-documenting) vs `AF_*` (terse). Decision + chosen prefix recorded in `next/server/README.md`.
- [ ] `./gradlew bootJar` produces `alpenflight-server-*.jar`.
- [ ] `./gradlew check` green.

### Web (Angular / npm)

- [ ] Spot-check: `next/web/package.json` currently has `"name": "web"` (already brand-neutral). Update only if any downstream artifact references a longer name. No npm-scope work expected.

### Docs

- [ ] All references to the *rewrite* in `docs/modernization/**/*.md` say "AlpenFlight" (not "FLS", not "the rewrite", not `next/`-as-product). References to the *legacy* product correctly continue to say "FLS" — the rename is semantic, not blind find-replace.
- [ ] `docs/modernization/00-seed.md`, `01-current-state.md`, `02-vision-and-constraints.md` updated where they reference the rewrite by name.
- [ ] Existing ADRs under `docs/modernization/adrs/` updated where they reference product naming. No new ADR for the rebrand itself.
- [ ] Top-level `CLAUDE.md` — the "Repository layout" line for `next/` reads `"the rewrite (AlpenFlight); layout + decisions in docs/modernization/adrs/"`.
- [ ] `next/CLAUDE.md` (if present), `next/server/CONVENTIONS.md`, and `next/server/README.md` (`# fls-server` → `# alpenflight-server`; example jar name updated) reflect the new name.
- [ ] Other in-repo READMEs under `next/` updated.

### Verification

- [ ] `grep -rE 'ch\.fls|fls-server|"FLS"' next/ docs/modernization/` returns only intentional legacy-reference matches (e.g. sentences about AlpenFlight being the rewrite of the legacy FLS product).
- [ ] CI green on the rename branch.

## Notes

- **Single atomic PR.** This rename touches many files; reviewers benefit from one diff that builds green rather than partial states across multiple commits.
- **Ordering vs S-120:** independent. Could be done before, after, or merged together at refinement. Operator decides whether to retire S-120 in favor of an extended scope here when S-120 itself is refined.
- **Domain choice:** `.ch` over `.aero` on cost (~CHF 12/yr vs ~CHF 80/yr); aviation-TLD signal judged not worth 7× the price for a DACH-niche product.
- **Why no ADR:** explicit operator preference. If a future contributor asks "why AlpenFlight?", point them at this story + the rebrand memory; promote to ADR only if the question recurs.

<!-- modernize-refine: start -->

## Design notes

### Module layout — files that change, by surface

This is a rename, not a feature build. No new files. The inventory below is the implementer's checklist, grouped by surface.

- **Server Java (`next/server/src/`)** — directory tree moves wholesale from `.../ch/fls/...` to `.../ch/alpenflight/...`. Three source sets: `main/java/ch/fls/`, `test/java/ch/fls/`, `nullawayDemo/java/ch/fls/`. Renamed class files:
  - `next/server/src/main/java/ch/fls/FlsApplication.java` → `.../ch/alpenflight/AlpenFlightApplication.java` (Spring Boot entry point).
  - All `package-info.java` files under each tree (5 in main, none in test, 1 in nullawayDemo).
- **Server Gradle (`next/server/`)** — `build.gradle.kts` (`group`, `description`, the NullAway comment on line 92, the Flyway env-var-default DB-name strings `fls`), `settings.gradle.kts` (`rootProject.name`).
- **Server resources (`next/server/src/main/resources/`)** — `application.yml` (`spring.application.name`), `application-dev.yml` (`logging.level.ch.fls` → `ch.alpenflight`; loopback `fls/fls/fls` datasource defaults stay — runtime infra, not brand). `application-test.yml` + `application-prod.yml` verified clean.
- **Server docs (`next/server/`)** — `README.md` (title, jar filename, every `ch.fls.*` package reference; the "Hello FLS" copy in the endpoint table **stays** — user-facing demo content, deferred to S-020), `CONVENTIONS.md` (`SharedPostgresContainer` FQCN on line 49, `ch.fls.server.testsupport` and `ch.fls.server.migration` example FQCNs on lines 47, 50, 53, 65, 72).
- **Server test fixtures** — `src/test/java/ch/fls/server/testsupport/SharedPostgresContainer.java` (`[fls-server]` log prefix on line 67), `src/test/resources/security/forbidden-migration-patterns.txt:2` (FQCN comment goes stale post-rename). `HelloController.java` `"Hello FLS"` literal + `HelloControllerIT.java` assertion **stay** — user-facing per story Context, allowlisted; flip with S-020.
- **Extract module (`next/database/extract/`)** — in scope. `build.gradle.kts` (`group`, `description`), `settings.gradle.kts` (`rootProject.name`), `README.md` (title `# fls-legacyextract`, all package paths), Java tree `src/main/java/ch/fls/legacyextract/` + `src/test/java/ch/fls/legacyextract/` → `.../ch/alpenflight/legacyextract/` (7 production + 3 test files). `FlsTestFixtureSeeder.java` → `LegacyExtractFixtureSeeder.java`. The Java identifier `FLS_TEST_ROOT` in `MetadataExtractorIntegrationTest.java:99` and the `[fls-extract]` log prefix in the same file (line 59) flip too. **Important nuance:** `FLS_TEST_ROOT` is a Java constant, not a shell env-var export — there is no exported `FLS_*` variable anywhere in the codebase today, so the env-var-prefix decision (below) is purely forward-looking.
- **Web (`next/web/`)** — technical layer only (see §9 below for the user-facing boundary). `angular.json` (`prefix: "fls"` → `"af"`), `eslint.config.mjs` (both `prefix: 'fls'` rules → `'af'`), `next/web/CLAUDE.md` (selector-prefix line on line 14, 24, 111, 123; replace the §6 line `"Component selector prefix: fls- (matches legacy continuity)"` with `"Component selector prefix: af- (AlpenFlight brand prefix; short by Angular convention)"`), `next/web/README.md` (heading "next/web — FLS modern frontend"; `<fls-root/>` reference on line 57). Selector elements `<fls-root>` and `<fls-landing>` rename to `<af-root>` / `<af-landing>` (technical DOM identifiers, not user-visible labels — in scope). `<title>FLS</title>` in `src/index.html` and `"Hello FLS"` landing-component text + spec assertion stay (user-facing per story Context, deferred to closer-to-cutover).
- **Ops (`next/ops/`)** — `pgadmin/servers.json` (label `"FLS Target Postgres"`), `pgadmin/Dockerfile` (comment), `dev-up-full.sh` (jar filename in bootRun arg line ~82, "FLS Target Postgres" / "Legacy SQL Server" banner text line ~91/98). The `PROJECT="fls-e2e"` Docker Compose project name on `dev-up-full.sh:39` **stays** — shared dev-tool name across legacy + next/, renaming drops every contributor's running containers; deferred to cutover band alongside repo/org rename. Repo-root `docker-compose.yml` (`image: fls-pgadmin:local` line 126) in scope; document `docker rmi fls-pgadmin:local` cleanup step.
- **Modernization docs (`docs/modernization/`)** — file-by-file pass (see §10). Seven stories match `ch.fls|fls-server|FLS_TEST_ROOT`. Nine ADRs match `\bFLS\b`; notable hits: ADR 0014:54 ("default FLS theme"), ADR 0019:163 ("FLS owns user.id"), ADR 0021:103 ("at FLS scale"). Top-level `/c/Users/roman/IdeaProjects/fls/CLAUDE.md` "Repository layout" line for `next/`.

**Confirmed out-of-scope:** `flsserver/`, `flsweb/`, `e2e/` (top-level Playwright), `.github/workflows/ci.yml` + `extract.yml` (all `fls-*` refs describe the legacy build path), Postgres DB credentials (`fls`/`fls`/`fls` — runtime infra), `FLSTest` SQL Server DB name (legacy fixture, owned upstream).

### Domain model + API surface

N/A — no entity changes, no controllers shift contract. `grep -rEn "FlsDto|FlsRequest|FlsResponse|class Fls" next/server/src/` returns only `FlsApplication.java`. Only API surface today is `HelloController` (`GET /api/v1/hello`); path stays the same. The actuator surface (`/actuator/health`, `/actuator/info`) is unaffected at the path level; the Spring `info.app.name` derived from `spring.application.name` will read `alpenflight-server` automatically.

### Integration with other stories

- **Inputs (`depends_on`):** none formally. Implicit input: S-001 (scaffold), S-009 (Flyway wiring), S-012 (test infra layout — `SharedPostgresContainer` and friends).
- **Outputs / downstream consumers:**
  - **S-120** (folder-slug rename) — independent. Execute S-128 *first*, then S-120 (rationale §2). S-120's diff stays a pure `git mv`; the two PRs read cleanly in isolation.
  - Every future story that grows new packages must use `ch.alpenflight.*` from day one. The `next/server/CONVENTIONS.md` update propagates that contract forward.
  - The `info.app.name` change cascades to **S-031** (JSON logging) — when MDC fields land, the app name in log lines will already be `alpenflight-server`. No coordination needed if S-128 ships first.

### 1. PR shape — single atomic, squash-merged

Confirm the story's "Single atomic PR" note. The Java package rename is, by construction, an IDE-driven N-file commit (every `import ch.fls.*` in every Java file flips at once). Splitting across multiple PRs means either (a) the codebase is in a broken-compile state between PRs, or (b) an awkward "both-packages-coexist" intermediate that is more work than the rename itself. Per operator memory `feedback-always-squash-merge.md`, fls PRs always squash-merge; that collapses any intermediate fix-up commits, giving idempotency for free (§11).

### 2. Execution sequence

Designed so the working tree compiles after each step (except step 1, which is mid-refactor in the IDE — covered by step 6).

1. **IntelliJ "Rename Package" on `ch.fls`** → `ch.alpenflight`. Run on the `next/server/` Gradle project AND the `next/database/extract/` Gradle project (open as separate IntelliJ projects or as modules of one). IDEA propagates: file moves, every `import ch.fls.*` rewrite, every FQN string literal IDEA recognizes (enable "Search in non-Java files" in the rename dialog). **This is the only step that's awkward without an IDE** — tool-fallback at the end.
2. **IntelliJ "Rename Class"** `FlsApplication` → `AlpenFlightApplication` (`next/server/`) and `FlsTestFixtureSeeder` → `LegacyExtractFixtureSeeder` (`next/database/extract/`). Class rename is a separate IDE refactor from package rename.
3. **Gradle config:** edit `next/server/build.gradle.kts` (`group = "ch.alpenflight"`, `description = "AlpenFlight server"`, line-92 NullAway comment), `next/server/settings.gradle.kts` (`rootProject.name = "alpenflight-server"`), `next/database/extract/build.gradle.kts` (`group = "ch.alpenflight.legacyextract"`, `description`), `next/database/extract/settings.gradle.kts` (`rootProject.name = "alpenflight-legacyextract"`).
4. **`application.yml`:** flip `spring.application.name`. Flip `logging.level.ch.fls` → `logging.level.ch.alpenflight` in `application-dev.yml`. Datasource creds + DB name stay `fls` (runtime infra).
5. **Log prefix:** `[fls-server]` → `[alpenflight-server]` in `SharedPostgresContainer.java:67`; `[fls-extract]` → `[alpenflight-extract]` in `MetadataExtractorIntegrationTest.java:59`. `HelloController` response body `"Hello FLS"` and its `HelloControllerIT` assertion are **out of scope** (user-facing, deferred to S-020).
6. **Build green check:** `./gradlew check` from `next/server/`, `./gradlew check` from `next/database/extract/`. **If green, the codebase is functionally renamed.** Remaining steps are docs + frontend.
7. **Frontend technical surfaces** (§9): `angular.json` prefix, `eslint.config.mjs` selector rules, `next/web/CLAUDE.md`, `next/web/README.md`, `<af-root>` / `<af-landing>` selectors via IDE/WebStorm refactor.
8. **Ops files:** `next/ops/pgadmin/servers.json`, `next/ops/pgadmin/Dockerfile`, `next/ops/dev-up-full.sh` (preserve the `PROJECT="fls-e2e"` line on `:39` — deferred), repo-root `docker-compose.yml` `image:` line; document the `docker rmi fls-pgadmin:local` cleanup.
9. **Docs sweep:** `next/server/README.md`, `next/server/CONVENTIONS.md`, `next/database/extract/README.md`, `next/web/README.md`, `next/web/CLAUDE.md`, top-level `/c/Users/roman/IdeaProjects/fls/CLAUDE.md`, modernization stories + ADRs (§10).
10. **Grep verification:** see §6 and the Test plan's docs-sweep grep.

**Tool-only fallback (no IntelliJ):** for each `.java` file under the three trees, (a) edit the first line `package ch.fls.X;` → `package ch.alpenflight.X;`, (b) `git mv` the directory `ch/fls/` → `ch/alpenflight/`, (c) edit every file under `next/server/` and `next/database/extract/` containing `ch.fls` to flip imports / FQNs. Grep-driven and error-prone — prefer the IDE path. File list to scan: `grep -rl 'ch\.fls' next/server/src/ next/database/extract/src/`.

### 3. Java package rename mechanics

- IDE refactor recommended. NullAway annotation-processor classpath is unaffected. The `nullaway { onlyNullMarked = true }` block in `next/server/build.gradle.kts:91-95` does **not** reference `ch.fls` by name — it relies on `@NullMarked` on `package-info.java`, which moves with the directory.
- Single-move (`ch.fls.*` → `ch.alpenflight.*`), not two-step. IDEA handles the wildcard rename in one go.
- No package name encodes semantic meaning beyond namespace. Verified across `ch.fls`, `ch.fls.config`, `ch.fls.platform`, `ch.fls.platform.hello`, `ch.fls.server.testsupport`, `ch.fls.server.migration`, `ch.fls.actuator`, `ch.fls.build`, `ch.fls.nullaway`, `ch.fls.legacyextract`. The extract module's leaf `legacyextract` carries the semantic and stays (§4).
- **Watch for FQN string literals.** Every `@SpringBootTest` class currently carries `@EnabledIf("ch.fls.server.testsupport.SharedPostgresContainer#available")`. IDEA may or may not catch these depending on its "Search in non-Java files" setting; manually grep for `"ch.fls` after the rename to verify.

### 4. Extract module decision — `ch.alpenflight.legacyextract`

**Recommendation: rename to `ch.alpenflight.legacyextract`** (the tool's owner is AlpenFlight; its input is legacy FLS; the leaf segment `legacyextract` carries the "extracts from legacy" semantic). Keeping `ch.fls.legacyextract` introduces a permanent cross-module naming inconsistency inside `next/`. Class `FlsTestFixtureSeeder` → `LegacyExtractFixtureSeeder`. The `FLSTest` SQL Server DB name in the legacy fixture stays — it's a literal SQL Server DB name pinned by `flsserver/database/FLSTest/`, owned by the legacy stack.

### 5. Env-var prefix — `ALPENFLIGHT_*`

**Recommendation: `ALPENFLIGHT_*`.** Single migration today: the Java constant `FLS_TEST_ROOT` → `ALPENFLIGHT_TEST_ROOT` in `MetadataExtractorIntegrationTest.java:99`. Rationale: `AF_*` collides with AlphaFold, AppFleet, Airflow, "Application Fingerprint" in shell namespaces. `ALPENFLIGHT_*` is greppable, copy-pasteable in CI logs, and unambiguous. Spring relaxed binding works identically with both prefixes — pick on collision/clarity. Record the decision in `next/server/README.md` per AC. Note: no exported `FLS_*` shell variable exists today, so this is a forward-looking policy with zero migration risk.

### 6. Boundaries — what the rename does and doesn't reach

In-scope and covered automatically by the changes above:
- Jar filename (covered by `rootProject.name`) — `bootJar` produces `alpenflight-server-0.0.1-SNAPSHOT.jar`.
- Spring `info.app.name` in `/actuator/info` — derived from `spring.application.name`.
- Prometheus/Micrometer common tags — currently not on the classpath; when wired (S-031), `application=alpenflight-server` flows automatically.
- Logback logger key `ch.fls` in `application-dev.yml` — explicit edit, must flip.

Out-of-scope (rationale, so the implementer doesn't second-guess):
- Postgres DB name/user/password (`fls`/`fls`/`fls`) — runtime infra credentials. Renaming ripples to dev/CI compose, every dev's local DB, Flyway plugin defaults. Separate cutover-band concern.
- Docker image registry repo names — no images pushed today.
- `e2e/` Playwright suite — self-contained per top-level CLAUDE.md; `fls-*` refs all describe legacy.
- `.github/workflows/ci.yml` + `extract.yml` — every `fls-*` ref describes the legacy build path.
- Pre-cutover, no consumer outside the repo (Proffix, OGN, monitoring) depends on the jar name, Spring app name, or log prefix. Implementer can proceed without nervousness.

### 7. DTOs / API contract impact

None. Verified: `grep -rEn "FlsDto|FlsRequest|FlsResponse|class Fls" next/server/src/` returns only `FlsApplication.java` (covered by §3 class rename). OpenAPI spec (S-003) is dormant; nothing to regenerate.

### 8. Fls-prefixed classes — complete inventory

Only two classes carry the `Fls` prefix (`find next/ -name "*Fls*.java"`):
- `next/server/src/main/java/ch/fls/FlsApplication.java` → `AlpenFlightApplication.java`
- `next/database/extract/src/test/java/ch/fls/legacyextract/FlsTestFixtureSeeder.java` → `LegacyExtractFixtureSeeder.java`

IDE class-rename handles imports + references; class rename is a separate IDE action from package rename.

### 9. Frontend — technical-vs-user-facing boundary

The story Context excludes "User-facing branding (UI labels, login screen, email-from address, customer-facing names)." Applied to `next/web/`:

**In scope (technical identifiers, lint config, internal docs):**
- `angular.json` `"prefix": "fls"` → `"af"`.
- `eslint.config.mjs` — both `prefix: 'fls'` rules → `'af'`.
- `<fls-root>` / `<fls-landing>` element selectors → `<af-root>` / `<af-landing>` (DOM identifiers, not user-visible labels; lockstep with the prefix change). IDE refactor (IntelliJ/WebStorm) propagates across templates + TS.
- `next/web/CLAUDE.md` — replace the §6 line `"Component selector prefix: fls- (matches legacy continuity)"` with `"Component selector prefix: af- (AlpenFlight brand prefix; short by Angular convention)"`. Update every `<fls-*>` example in the file.
- `next/web/README.md` heading `# next/web — FLS modern frontend` and `<fls-root/>` reference.

**Out of scope (user-facing, deferred to closer-to-cutover):**
- `<title>FLS</title>` in `src/index.html` — browser tab text.
- `"Hello FLS"` in `landing.component.ts` template + spec assertion — visible UI content.

**Why `af-` and not `alpenflight-`:** Angular convention prefers short, two-letter brand prefixes (`mat-`, `cdk-`, `ng-`). `alpenflight-` is too long for an HTML element prefix. Collision risk that matters for env vars doesn't apply to template-local custom elements (scoped to your own templates).

### 10. Docs-sweep workflow — file-by-file review, not blind sed

The rebrand is **semantic, not lexical**: legacy product stays "FLS", legacy DB stays `FLSTest`, legacy server stays `flsserver/`, references to *the rewrite* flip to AlpenFlight. A blind `sed s/FLS/AlpenFlight/g` corrupts every legacy reference.

Workflow:
1. `grep -rnE 'ch\.fls' next/ docs/` — every match is a rename target (Java package).
2. `grep -rnE 'fls-server' next/ docs/` — every match is a rename target.
3. `grep -rnE '\bFLS\b' docs/modernization/` — review each match; rewrite-context → AlpenFlight, legacy-context → leave.
4. `grep -rnE 'FlsApplication|FlsTestFixture|FLS_TEST_ROOT|Hello FLS' next/ docs/` — every match is a rename target (modulo the Hello-FLS ODQ).

Track the doc-sweep file list in the PR description as a checkbox so the reviewer can confirm completeness. File set: ~25 files (7 stories + 9 ADRs + a handful of READMEs/CLAUDE.md). Hours, not days.

### 11. Idempotency

Single squash-merge to `main` guarantees `main` is never in a half-renamed state. During the branch's life, intermediate commits may be incoherent — branch state is private until merge. Squash collapses to one commit; if CI fails on `main` (shouldn't, if branch CI is green), `git revert` is one commit. If CI fails mid-PR on the branch, add fix-up commits — do not amend (per the operator's standing policy: always create NEW commits).

### 12. Alternatives considered

- **Chosen: single atomic PR, squash-merged.** Mechanical, homogeneous, one-time reviewer cost.
- **Rejected: phased rename across multiple PRs by surface.** Each phase needs its own grep-verification cycle; reviewer context cost across multiple sittings is higher; the IDE-driven refactor is itself atomic — splitting requires undoing what the IDE just did.
- **Rejected: bundle with S-120.** Different reviewer-mental-model (`git mv` vs package rename). Keeping them separate gives two clean git-log commits archaeologists can read in isolation.

## Edge cases & hidden requirements

### Scope gaps in the acceptance criteria

- **`next/database/extract/` module** — has its own `ch.fls.legacyextract.*` packages (7 production + 3 test files) and its own `build.gradle.kts` (`group = "ch.fls.legacyextract"`). Story AC only enumerates `next/server/`. **Disposition: in scope.** Rename to `ch.alpenflight.legacyextract`, rename class `FlsTestFixtureSeeder` → `LegacyExtractFixtureSeeder` (preserves "legacy" semantic precisely), Gradle `rootProject.name = "alpenflight-legacyextract"`.
- **`[fls-extract]` log-prefix in extract module** — `next/database/extract/.../MetadataExtractorIntegrationTest.java:59`. Story AC cites only `SharedPostgresContainer.java`. **Disposition: in scope** under "All log-prefix strings".
- **`next/ops/pgadmin/servers.json`** — `"Name": "FLS Target Postgres"` is the developer-visible pgAdmin connection label. **Disposition: in scope.**
- **`next/ops/pgadmin/Dockerfile`** — comment `"# Custom pgAdmin image: same as upstream + pre-wired FLS Target Postgres"`. **Disposition: in scope** (cosmetic but otherwise confusing).
- **`next/ops/dev-up-full.sh`** — jar filename in bootRun arg, banner text "FLS Target Postgres" / "Legacy SQL Server". **Disposition: in scope.** The `PROJECT="fls-e2e"` Docker Compose project name on `:39` **stays** (operator decision): shared with legacy dev tooling; renaming drops every contributor's running containers and requires coordinated legacy-script updates. Deferred to cutover band.
- **Repo-root `docker-compose.yml:126`** — `image: fls-pgadmin:local` locally-built Docker image tag. **Disposition: in scope.** Renaming orphans existing locally-built images; document `docker rmi fls-pgadmin:local` as a one-time cleanup step.
- **`application-dev.yml:23`** — `logging.level.ch.fls: DEBUG` is a YAML key whose value is the package prefix. Silently becomes a no-op if not flipped to `ch.alpenflight` alongside the Java package rename. **Disposition: in scope** (covered by Execution step 4).
- **`next/web/angular.json:14`** — `"prefix": "fls"` is the Angular CLI's `ng generate component` selector default. Not flipping it means newly generated components still get `fls-` selectors. **Disposition: in scope** (with the `af-` recommendation per §9).
- **`next/web/eslint.config.mjs:20,24`** — both selector-prefix lint rules. **Disposition: in scope.**
- **`next/server/src/test/resources/security/forbidden-migration-patterns.txt:2`** — comment cites `ch.fls.server.migration.MigrationFolderConventionsTest` by FQCN. Goes stale post-rename. **Disposition: in scope** (comment-only edit).
- **ADR brand mentions used to mean the *new* system** — ADR 0014:54 ("default FLS theme"), ADR 0019:163 ("FLS owns user.id"), ADR 0021:103 ("at FLS scale"). All three describe AlpenFlight, not legacy. **Disposition: in scope** under "Existing ADRs ... updated where they reference product naming".
- **`next/server/CONVENTIONS.md` FQCN citations** — lines 47, 50, 53, 65, 72 cite `ch.fls.server.testsupport.*` and `ch.fls.server.migration.*` as canonical example paths. All go stale post-rename. **Disposition: in scope.**

### Semantic-vs-blind-replace traps (preserve these)

The verification grep MUST NOT flag these as "missed rename":
- Legacy folder paths: `flsserver/`, `flsweb/`.
- Historic-context sentences in docs: "AlpenFlight is the rewrite of the legacy FLS system", "extracted from FLS legacy", etc.
- `FLSTest` SQL Server DB name in legacy fixtures (pinned by `flsserver/database/FLSTest/`).
- `docs/legacy/server.md` + `docs/legacy/web.md` mental-model docs (about the legacy system).
- `.github/workflows/ci.yml` legacy build steps + `extract.yml` legacy-input refs.
- Postgres DB credentials `fls/fls/fls` — runtime infra, not brand.
- Test-infrastructure container names + creds (`fls_test`, `fls_test_pw`, `fls-pg-test-` prefix in `PostgresTestContainerLifecycle`) — **see "Test-infrastructure credential names" below**.
- `PROJECT="fls-e2e"` Docker Compose project name in `dev-up-full.sh:39` (operator-decided: stays; deferred to cutover band).
- `HelloController` `"Hello FLS"` response literal + `HelloControllerIT` assertion (operator-decided: stays; user-facing per Context; flips with S-020).

### Generated / cached / vendored artifacts (grep-exclude)

`next/server/build/`, `next/database/extract/build/`, `.gradle/`, `node_modules/`, `next/web/.angular/cache/`, `next/web/dist/`, `.git/`. The QA-engineer's grep includes the right `--exclude-dir` set.

### Test-infrastructure credential names

`fls_test` / `fls_test_pw` / `fls-pg-test-` container prefix in `PostgresTestContainerLifecycle`. **Disposition: implementer choice — rename or allowlist; document in PR.** These are test plumbing, not product branding; behavior is identical either way. The pragmatic call is to rename for consistency (`alpenflight_test` etc.), but allowlisting them costs nothing and the legacy name parity (matching the `fls/fls/fls` Postgres dev creds) has weak symmetry value.

### Env-var prefix — resolved default

`ALPENFLIGHT_*`. Single migration today: the Java constant `FLS_TEST_ROOT` → `ALPENFLIGHT_TEST_ROOT`. Forward-looking policy — no exported `FLS_*` shell variable exists in the codebase.

### Ordering with S-120 — S-128 first, then S-120

Both specialists converge: do S-128 first. Rationale: S-120 is a pure `git mv` of `next/`; running it after S-128 keeps each PR's git history reviewable separately (package rename vs folder move). If S-120 ran first, every AC path in S-128 would need updating. Bundling is the third option but multiplies reviewer surface unnecessarily.

### Verification grep precision

The story's grep `'ch\.fls|fls-server|"FLS"'` is incomplete. Expanded pattern:

```
grep -rnE 'ch\.fls|fls-server|fls_server|"FLS"|'\''FLS'\''|FLS-[A-Z]|FLS_[A-Z]|\bfls\b|FlsApplication|FlsTestFixture|Hello FLS' \
  --include="*.java" --include="*.kt" --include="*.kts" \
  --include="*.yml" --include="*.yaml" --include="*.json" \
  --include="*.md" --include="*.txt" --include="*.html" --include="*.ts" \
  --exclude-dir=".git" --exclude-dir="node_modules" --exclude-dir="build" \
  --exclude-dir=".gradle" --exclude-dir=".angular" --exclude-dir="dist" \
  --exclude-dir="flsserver" --exclude-dir="flsweb" \
  next/ docs/modernization/
```

Distinguishing intentional from unintentional hits: commit a `.rebrand-allowlist` file (annotated with rationale per line) and pipe the grep output through it pre-merge. See Test plan §"Docs-sweep verification" for the allowlist contents.

### Git history & blame

A Java package rename via IDE refactor resets `git blame` on every renamed file. `git blame -C -M` mitigates partially (detects renames), but the standard blame view loses line-level history. **Operator decision: no `.git-blame-ignore-revs`** — accept the blame-noise on first-line-of-renamed-files as one-time cost; `-C -M` is the fallback.

### CI / build cache invalidation

Gradle local build cache is keyed by task inputs including `group`. Changing `group = "ch.fls"` → `"ch.alpenflight"` invalidates all cached server compilation tasks on first post-rename build for every contributor. One-time slowdown; no ongoing impact. Remote Gradle cache (if wired in CI later) orphans old entries until TTL — negligible storage cost. IDE indices (IntelliJ) re-index on first open — minutes, not hours.

### Externally-visible names — pre-cutover, no consumers

The rename creates new external-looking names: jar artifact (`alpenflight-server-*.jar`), Spring app name in `/actuator/info`, log prefix, future Prometheus `application` tag. **Pre-cutover, no consumer depends on these.** Proffix sync, OGN, monitoring dashboards — none are wired to `next/` yet. Implementer can proceed without coordination.

### NFR implications

None expected. Audit-log infrastructure (S-027) is not wired; no event names carry the brand string. No Micrometer/Prometheus dependency on the classpath yet; no `fls_*` metric prefix exists. No log-correlation fields carry the brand. Performance unchanged. Security surface unchanged.

### Hidden references swept

- `find next/ops -type f` covered. `docker-compose.yml` repo-root included.
- Dockerfile `LABEL` directives: only the comment in `next/ops/pgadmin/Dockerfile` — no `LABEL maintainer="fls.*"` style annotations.
- Helm chart values: none today (deferred to S-046).
- `Caddyfile`: not yet wired.
- GitHub Actions workflow names: `ci.yml` + `extract.yml` reference the legacy build path — out of scope per Context.

### Acceptance-criterion ambiguities

- **"All log-prefix strings updated"** is fuzzy. Make it precise via a ratchet test (see Test plan §Unit tests, `no_fls_server_log_prefix_in_source_tree`).
- **"Other in-repo READMEs under `next/` updated"** is too narrow — ops scripts (`dev-up-full.sh`), pgadmin assets, ADRs that use "FLS" to mean the new system are not READMEs. The expanded surface inventory above replaces this AC bullet.
- **"CI green on the rename branch"** is unambiguous but cite the specific jobs: `./gradlew check` (both modules), `./gradlew bootJar` (server), web build + tests (if web touched).

## Security plan

(N/A — no security signal in story scope; no authz, tenant, PII, or audit surface change. The rename is semantically transparent at runtime. Re-spec if S-128 later acquires one.)

## Test plan

### Test pyramid for this story

- **Unit:** 6 new tests (ratchet assertions over classpath, YAML, Gradle metadata, source-tree grep; no Spring context).
- **Integration:** 0 new — all existing `@SpringBootTest` tests become the implicit boot-and-schema regression net post-rename.
- **E2E:** none — no user-visible behavior changes.
- **Parity:** none (`parity_test: none`). Runtime behavior is identical pre/post. "Parity" here is the pre/post test-count comparison.

### Unit tests (new — ratchet against regression of the rebrand)

All in `next/server/src/test/java/ch/alpenflight/build/RebrandConventionsTest.java`. Plain JUnit 5, no Spring context, ms-speed — pattern matches `ToolchainTest` / `MigrationFolderConventionsTest`.

- `no_class_under_ch_fls_package_exists` — `Files.walk` over `src/main/java/` (walk-up-to-root anchor like `TenantCatalogYamlTest.locateTenantRules`); assert zero `.java` files contain `^package ch\.fls`.
- `no_class_under_ch_fls_in_test_sources` — same walk over `src/test/java/`; covers `testsupport`, `migration`, `nullawayDemo`; assert zero hits on `^package ch\.fls`.
- `spring_application_name_is_alpenflight_server` — load `application.yml` from classpath, parse with SnakeYAML (already on test classpath via Spring Boot), assert `spring.application.name == "alpenflight-server"`.
- `no_fls_server_log_prefix_in_source_tree` — walk `src/main/java/` + `src/test/java/`; assert zero `.java` file contains literal `[fls-server]` (or `[fls-extract]` for the extract module's equivalent ratchet). Strip `//`-comment lines before matching (same pattern as `MigrationFolderConventionsTest.no_forbidden_patterns_in_migrations`).
- `gradle_group_is_ch_alpenflight` — read `build.gradle.kts` filesystem-side (walk-up anchor); assert contains `group = "ch.alpenflight"` and does NOT contain `group = "ch.fls"`.
- `settings_root_project_name_is_alpenflight_server` — read `settings.gradle.kts`; assert contains `rootProject.name = "alpenflight-server"` and does NOT contain `rootProject.name = "fls-server"`.

Equivalent ratchet for the extract module (`next/database/extract/src/test/java/ch/alpenflight/legacyextract/RebrandConventionsTest.java`): mirror tests 1–2, 5–6 against the extract module's surfaces; test 4 mirrored with `[fls-extract]`.

### Integration tests

None new. Post-rename, `./gradlew check` must produce green results for all existing tests — they collectively prove the renamed app still boots and serves identically. Implicit regression net: `ApplicationContextTest.contextLoads`, `ActuatorHealthIT.actuatorHealthReturns200AndStatusUp`, `FlywayBootstrapIntegrationTest.*` (9), `TenantCatalogConsistencyTest.*` (3), `IdentityBaselineIntegrationTest.*` (18), `HelloControllerIT`, `MigrationFolderConventionsTest`, `ToolchainTest`, `TenantCatalogYamlTest`. None need modification beyond updated `package` + `import` lines from the IDE rename refactor.

### E2E + Parity

(none — no behavior change.)

### Test fixtures + record-keeping

**Pre-flight baseline (manual step, captured in PR description):**
- `./gradlew clean check` test count.
- `ls build/libs/` — current jar filename.

**`.rebrand-allowlist`** at repo root — plain-text file listing patterns the docs-sweep grep is permitted to match, each annotated with rationale. The file remains in the repo as the permanent record of intentional legacy references. Sample contents:

```
# V2 migration comment: "...FLS identity..." — historical context
next/server/src/main/resources/db/migration/V2__identity_and_reference.sql
# HelloController "Hello FLS" — S-020 will gate/remove; user-facing per S-128 Context
next/server/src/main/java/ch/alpenflight/platform/hello/HelloController.java
next/server/src/test/java/ch/alpenflight/platform/hello/HelloControllerIT.java
# web index.html <title>FLS</title> — user-facing, out of scope per S-128 Context
next/web/src/index.html
# web landing component "Hello FLS" — user-facing, out of scope
next/web/src/app/features/landing/landing.component.ts
next/web/src/app/features/landing/landing.component.spec.ts
# Test-infra credentials (decision: allowlist) — see S-128 Test-infrastructure credential names
next/server/src/test/java/ch/alpenflight/server/testsupport/PostgresTestContainerLifecycle.java
```

### Build verification (the primary gate)

1. **Pre-flight baseline** (before touching any file):
   ```
   cd next/server && ./gradlew clean check
   cd ../database/extract && ./gradlew clean check
   ```
   Record: test counts and current jar filenames.

2. **Warm-cache check** (after rename): `./gradlew check` in each module. Gate: green; test count equals baseline.

3. **Cold-cache check**: `./gradlew clean check`. Proves no stale `.class` under `ch/fls/` is silently being picked up.

4. **Jar name check**: `./gradlew bootJar && ls build/libs/`. Output contains `alpenflight-server-*.jar`, does NOT contain `fls-server-*.jar`.

5. **Test count parity**: compare pre/post counts via `./gradlew test --info 2>&1 | grep -E "tests were found|tests passed" | tail -5`. Paste both into PR.

### Docs-sweep verification

Run the expanded grep from Edge cases §"Verification grep precision". For every surviving hit, categorize as: (a) intentional legacy-product reference → add to `.rebrand-allowlist`; (b) missed rename → fix; (c) user-facing item explicitly excluded per Context → add to `.rebrand-allowlist` with the story reference. The grep (with `--include` filters + allowlist excludes) must produce zero unaccounted hits before merge. Paste the clean output into the PR.

### CI gates (must be green before merge)

1. `./gradlew clean check` in `next/server/` — all unit + integration tests green; post = pre test count.
2. `./gradlew clean check` in `next/database/extract/` — same.
3. `./gradlew bootJar` in `next/server/` — artifact matches `alpenflight-server-*.jar`.
4. The 6 new `RebrandConventionsTest` assertions green (run as part of `./gradlew check`).
5. Docs-sweep grep returns zero unaccounted hits.
6. Web build (if `next/web/` touched): `pnpm run build` + `pnpm test` green.

### Test-evidence record-keeping (paste into PR description)

```
Pre-rename:  next/server ./gradlew clean check → N tests, N passed. build/libs/fls-server-0.0.1-SNAPSHOT.jar
             next/database/extract ./gradlew clean check → M tests, M passed.
Post-rename: next/server ./gradlew clean check → N tests, N passed. build/libs/alpenflight-server-0.0.1-SNAPSHOT.jar
             next/database/extract ./gradlew clean check → M tests, M passed.
New ratchet tests added: RebrandConventionsTest (6 in server, 4 in extract)
Docs-sweep grep: 0 unaccounted hits (allowlist: .rebrand-allowlist, K entries)
```

### Manual-verification checklist (eyeball after `./gradlew bootRun`)

1. Startup log line reads `Started AlpenFlightApplication` (not `FlsApplication`). Spring banner mentions `alpenflight-server`.
2. `curl -s http://localhost:8080/actuator/health | jq .` → `{"status":"UP",...}`.
3. `curl -s http://localhost:8080/api/v1/hello` → expected body depends on ODQ resolution.
4. `find next/server/build -name "*.class" -path "*/ch/fls/*"` returns zero after `./gradlew clean`.
5. `./gradlew dependencies | grep "ch.fls"` returns zero.

### Risks

- **Stale Gradle build cache masking broken state** — cold-cache check (step 3) is mandatory. The `RebrandConventionsTest` source-tree walk catches this independently.
- **`@EnabledIf` FQCN string literals.** Every `@SpringBootTest` carries `@EnabledIf("ch.fls.server.testsupport.SharedPostgresContainer#available")`. IDE refactor may not update strings. Grep `"ch.fls` post-rename to catch these.
- **`application-dev.yml` `logging.level.ch.fls`** — silent DEBUG-logging loss if not flipped. Docs-sweep grep catches it.
- **NullAway `package-info.java` moves with the directory** — verified safe; package-info files move as part of the IDE rename. Cold build catches any miss (compilation fails if `@NullMarked` is absent at expected package).

## Performance plan

(N/A — no performance signal in story scope; no queries, indexes, caching, or hot-path concerns. The rename is runtime-transparent. Re-spec if S-128 later acquires one.)

<!-- modernize-refine: end -->

