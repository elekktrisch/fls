---
id: S-128
title: Technical rebrand FLS → AlpenFlight (packages, configs, docs)
epic: E-14
status: todo
estimate: M
parity_test: none
depends_on: []
adr_refs: []
refined: false
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
