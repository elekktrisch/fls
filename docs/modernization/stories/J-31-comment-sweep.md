---
id: J-31
title: Comment sweep — delete every comment, restore understanding through names
epic: cross-cutting (E-01 foundations; E-13 proof gate)
status: in_progress
started_at: 2026-08-14
journey0: false
carved: true
depends_on: [J-16, J-17]
rolls_up: []          # no S-stories; supersedes the [COMMENT-STRIP] boyscout rider
acceptance:
  - "[happy] An anonymous visitor loads `/` under the real IdP after the sweep — `landing` + `landing-topbar` visible, no Keycloak redirect, no console errors — and the pass video is tagged `journey:J-31` so the operator's single-bookmark gallery page is non-empty"
  - "[happy] `node .claude/skills/comment-strip/scripts/strip.mjs --check alpenflight e2e` exits 0 on the final head — zero prose comments, zero leftover `RENAME:` markers"
  - "[happy] That `--check` runs as a CI step on EVERY push, never behind a path filter — a docs-only or workflow-only delta still executes it (a path-skipped guard reads green having run nothing)"
  - "[happy] The same `--check` is a stage in `alpenflight/web/scripts/preflight.sh`, so a re-added comment reds locally before it reaches CI"
  - "[happy] The policy repeal is the FIRST commit on the branch, before any file is stripped — do-ship + do-task SKILL.md, `alpenflight/server/CONVENTIONS.md`, the eslint `bypassSecurityTrust` message, `.gitignore`, and the memory rewrite"
  - "[key-error] `strip.mjs` walks past a dangling symlink instead of dying on it — `alpenflight/web/node_modules_sandbox` points at a path absent on this box (permanent per `web/CLAUDE.md` §9) and today makes `collectSourceFiles` throw ENOENT, so the sweep cannot start at all"
  - "[happy] All 10 batches land, each as a `strip` commit plus a SEPARATE `renames` commit, each green on its cheap gate — mechanical and judgment never share a diff"
  - "[happy] All 58 Flyway migration files are stripped AND the LAN Postgres is repaired — backend boots, `flyway validate` passes, whole server IT suite green against the repaired history"
  - "[happy] The serial rename pass ends with `rg 'RENAME:' alpenflight e2e` empty, and the regenerated OpenAPI snapshot + orval client are committed in the SAME commit as the renames that caused them"
  - "[edge] The only comments left anywhere are tool-parsed directives and `// ext:` markers; every `ext:` marker is listed in the final report with its justification, and any a `@JsonProperty`/`@Column` pin could carry is a pin instead"
  - "[edge] The final report states the number of above-threshold manifest entries no judge reviewed — `0`, or the honest count; a silent cap must not read as full coverage"
  - "[happy] The PR's own `pull_request` checks pass on the merge head with the heavy jobs EXECUTED"
screen: "`/` — the public landing (J-16's screen, unchanged). The sole user-facing page in the proof."
headless_pulled_in: "none — the sweep's own regression guard is the `--check` detector, homed in CI + preflight"
migration: "N/A — no entity migrated. Flyway checksums DO change (58 migration files stripped) → repair required, see Notes."
parity_test: alpenflight/web/e2e/tests/real-idp/public-routes.spec.ts
adr_refs: [0022]
---

## Context

Every comment a human wrote for a human comes out of `alpenflight/` and `e2e/`, and
where a comment carried real understanding that understanding moves into the **name**.
History stays in git; rationale stays in `docs/modernization/`. The measured surface is
**21,031 comments across 1,799 files** (`strip.mjs --check alpenflight e2e`, T-01).

The journey changes no behavior, so its user-facing proof is the thinnest already-built
screen — the public landing at `/` — re-proven green. Same logic as Journey-0: no feature
risk competes with the sweep, and a rendering landing page over an entirely rewritten tree
is exactly the claim worth showing.

## Spec must assert

`public-routes.spec.ts:203-235` already captures the landing proof video, tagged
`journey:J-16`. Re-tag it `journey:J-31` and keep its assertions:

- `/` loads anonymously — `new URL(page.url()).host` is not the Keycloak host.
- `landing` and `landing-topbar` testids visible.
- `watchConsoleErrors` clean (the suite-wide guard makes any unstubbed `/api/v1` call a hard fail).
- Full-page screenshot + pass video, `proofVideo` called in `finally` AFTER `ctx.close()`.

The gallery bookmark guard (ci.yml `alpenflight proof (real-idp, clean-seed)`, step 28)
reds on zero videos tagged to the current journey — the re-tag is what keeps it green, not
an extra spec.

## Tasks

Batches run smallest-first so the pipeline is shaken out on 16 files, not 787.
`migrations-sql` runs LAST of the batches because it invalidates the Flyway checksums every
later backend gate depends on — the repair follows immediately.

- [x] **T-01** — `strip.mjs`: survive the dangling `node_modules_sandbox` symlink; regression case in `strip.test.mjs`; shake down `--check` + `--manifest` on a real sample
- [x] **T-02** — the repeal commit: do-ship + do-task SKILL.md, comment-strip SKILL.md (relax same-day + open-branch preconditions), `CONVENTIONS.md`, the eslint message, `.gitignore` (`.comment-strip/` + `backend.log`/`backend.pid`), retire `[COMMENT-STRIP]` from `_BOYSCOUT.md`, rewrite the memory
- [x] **T-03** — batch `migration-tool` (180 comments / 16 files) — pilot: proves the judge→strip→gate loop end to end
- [x] **T-03b** — manifest scoring: group consecutive `//` lines into ONE scored entry. The pilot proved a 10-line `//` block scores 1.5–5.9 per line while an equivalent `/** */` block scores 13, so the densest rationale in the batch (`BundleWriter.java:39`) landed at **1.7** — a judge reading ≥8-first would systematically skip exactly the comments worth keeping. Must land before `server-main` (787 files)
- [x] **T-04** — batch `database` (324 / 33)
- [x] **T-05** — batch `ops-shell` + `auth` (492 / 16)
- [x] **T-05b** — heredoc bodies are opaque to BOTH the stripper and `--check`: `normalize-realm-export.sh:21-114` is a Python program inside `<<'PYEOF'` whose 6 comment blocks + docstring are invisible (33 comments tree-wide). A silent hole is the third of its kind this journey (`node_modules.windows`, the `build`/`target` exclusion) and it falsifies the "only directives and `ext:` markers survive" AC. Lex heredoc bodies by **interpreter** — a body piped to `python3`/`node`/`psql` is a program; one echoed to stdout (`INFO` banners, operator help text) is DATA and must not be touched. Must land before T-11 (`preflight.sh`, `e2e/scripts/dev-up.sh`)
- [x] **T-06** — batch `build-config` — the per-module `build.gradle.kts` / `settings.gradle.kts` under a module path are swept by that module's batch (T-03 already took migration-tool's 21); this batch is only what no module batch claims
- [x] **T-07** — batch `migration-bundle` (1,413 / 126)
- [x] **T-08** — batch `web-src` (2,324 / 337)
- [ ] **T-08b** — `strip.mjs` is blind to `<!-- -->` comments inside Angular **inline** `template:`/`styles:` literals: they are string content, so neither the stripper nor `--check` sees them. **73 survive across 23 files in `web-src` alone** after T-08, and `--check alpenflight/web/src` still exits reporting only RENAME markers — a green gate over unswept prose. Falsifies the "only directives and `ext:` markers survive" AC and is the fourth silent hole this journey (after `node_modules.windows`, the `build`/`target` exclusion, and T-05b heredocs). Lex inline templates as HTML, keep the token-stream proof over the surrounding literal. Must land before T-15 wires the gate.
- [ ] **T-09** — batch `server-test` (3,795 / 319)
- [ ] **T-10** — batch `server-main` (5,360 / 787)
- [ ] **T-11** — batch `web-e2e` + `e2e/` + `web/scripts` (6,736 / 149 — densest)
- [ ] **T-12** — batch `migrations-sql` (1,946 / 58) — checksums change here
- [ ] **T-13** — `flyway repair` the LAN Postgres; backend boots, `flyway validate` passes, whole server suite green against the repaired history
- [ ] **T-14** — serial rename pass: collect every `RENAME:` marker, dedup, apply in ~20-groups, regenerate the OpenAPI snapshot + orval client in the SAME commit
- [ ] **T-15** — wire `--check` into `ci.yml` (every push, no path filter) + a `preflight.sh` stage — LAST, so the gate lands green instead of sitting red for the whole journey. ALSO correct the skill's batch table from what shipping it taught: `database`'s gate is a bare task in the standalone `alpenflight/database/extract` build (`rootProject.name = alpenflight-legacy-extract`), NOT `:extract:compileJava`; per-module `build.gradle.kts` belong to their module's batch, not batch 10; shard `web-e2e` on comment count (~200), not file count; the table needs a **module-root config** clause — `alpenflight/web/orval.config.ts` and `eslint.config.mjs` sit outside every glob yet the final `--check alpenflight e2e` gate reds on them
- [ ] **T-16** — re-tag the landing proof video `journey:J-31` in `public-routes.spec.ts`; verify the deployed preview bookmark renders it
- [ ] **T-17** — §4 gate: local real-idp green, then the PR's own checks job-level green on the merge head

## Notes

**Operator override on the 60/40 rule (2026-08-14).** `/do-plan` says pure tech-debt never
earns its own journey. The operator carved this one anyway and named the landing page as its
proof. Recorded as a decision, not a drift. Whether the 60/40 marker in `do-plan/SKILL.md`
reverts after this journey is `/do-retro`'s call.

**Scope is comment-strip only** (operator, 2026-08-14). No rider burndown rides along — a
1,799-file diff conflicts unresolvably with every open branch, so the merge window is the
risk to minimize. Other riders keep draining on feature journeys.

**Same-day merge: RELAXED** (operator, 2026-08-14). The skill's precondition assumes a team whose
open branches would conflict unresolvably. Two people work this repo and no other journey is in
flight, so the sweep takes the time it needs. T-02 relaxes the precondition in the skill itself
rather than ignoring it here.

**Gate wiring runs LAST (T-15), not in the repeal commit.** The skill puts `--check` in step 1 so
policy can't decay. Landing it first would red CI on 21,031 violations for the journey's entire
length, hiding every real red behind an expected one. The POLICY repeal still lands first (T-02) —
that's the half that stops a worker writing fresh comments — and the gate lands when it can land
green. Deviation is deliberate; the skill's reasoning holds for a busier repo.

**Base:** `origin/main` (e83d3bc4) + cherry-picked `24336e36` (J-17 retro) + `6ff70380`
(the comment-strip skill, lexer, judge agent). Both forked off the same `origin/main`, so no
squash-merge divergence.

**Measured batch sizes** — drives sharding, not guesswork:

| Batch | Comments | Files |
| --- | --- | --- |
| `server-main` | 5,360 | 787 |
| `server-test` | 3,795 | 319 |
| `web-src` | 2,324 | 337 |
| `web-e2e` (+ `e2e/`, `web/scripts`) | 6,736 | 149 |
| `migrations-sql` | 1,946 | 58 |
| `migration-bundle` | 1,413 | 126 |
| `ops-shell` (+ `auth`) | 492 | 16 |
| `database` | 324 | 33 |
| `migration-tool` | 180 | 16 |

`web-e2e` is the densest at ~45 comments/file — that is the `fan-out-parity-fixture.ts`
narration the `[COMMENT-STRIP]` rider named. This journey supersedes that rider; delete its
bullet from `_BOYSCOUT.md` in the repeal commit.

**Batch 8 (Flyway) is IN scope** (operator, 2026-08-14 — chose strip-and-repair over skip).
Stripping the 58 applied migrations changes every checksum, so `flyway validate` fails on the
LAN Postgres this box runs all ITs against, and on every other dev box. `clean-disabled: true`
means no easy recreate — plan `flyway repair`, then re-verify the backend boots and the whole
server suite is green before calling the batch done. The repair recipe belongs in the final
report, not just in this file.

**Blocking defect, found at carve.** `collectSourceFiles` uses `statSync`, which throws on
`alpenflight/web/node_modules_sandbox` — a symlink to `/home/agent/fls-build/…`, absent here
and documented as permanently broken in `web/CLAUDE.md` §9. `--check alpenflight e2e` dies
before scanning anything. Fix the walker (skip unresolvable entries) first; every other task
depends on it.

**Clean-tree precondition.** The skill refuses on a dirty tree. `alpenflight/server/backend.log`
and `backend.pid` are untracked in every working tree today and no `.gitignore` covers them —
fold that line into the repeal commit's `.gitignore` edit (which already adds `.comment-strip/`),
since it unblocks the precondition rather than riding as separate debt.

**Local-run constraints** (2-core box): never run Gradle and Playwright concurrently — batch
gates run serially. `generateOpenApiSnapshot` boots JPA, so source the `DATASOURCE_*` vars from
`~/.bashrc` first. ITs use the LAN Postgres in external mode; do not spin `alpenflight-pg-test-*`
containers.

**Likely task seams** (non-binding, seam granularity):
- the `strip.mjs` walker's symlink handling + a regression case in `strip.test.mjs`
- the policy repeal edits (5 files + `.gitignore` + the memory rewrite) as one commit
- the `--check` CI step + its path-filter exemption
- the `preflight.sh` stage
- one seam per batch — 10 of them, each `strip` + `renames`
- the Flyway repair + full-suite re-verify
- the serial rename pass + the OpenAPI/orval regeneration
- the `public-routes.spec.ts` proof re-tag
