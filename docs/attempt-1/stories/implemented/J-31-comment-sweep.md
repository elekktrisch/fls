---
id: J-31
title: Comment sweep — delete every comment, restore understanding through names
epic: cross-cutting (E-01 foundations; E-13 proof gate)
status: done
started_at: 2026-08-14
done_at: 2026-08-15
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
migration: "N/A — no entity migrated. Flyway checksums DO change (58 migration files stripped) → repair required, see Outcome."
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

`public-routes.spec.ts` already captures the landing proof video, tagged
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

- [x] **T-01** — `strip.mjs`: survive the dangling `node_modules_sandbox` symlink; regression case in `strip.test.mjs`
- [x] **T-02** — the policy repeal commit: do-ship / do-task / comment-strip `SKILL.md`, `CONVENTIONS.md`, the eslint message, `.gitignore`, `_BOYSCOUT.md`, the memory
- [x] **T-03** — batch `migration-tool` (180 comments / 16 files) — pilot for the judge→strip→gate loop
- [x] **T-03b** — manifest scoring: group consecutive `//` lines into ONE scored entry, so a judge reading ≥8-first stops skipping the densest rationale
- [x] **T-04** — batch `database` (324 / 33)
- [x] **T-05** — batch `ops-shell` + `auth` (492 / 16)
- [x] **T-05b** — lex heredoc bodies by **interpreter**: a body piped to `python3`/`node`/`psql` is a program and is swept; one echoed to stdout is operator-facing DATA and is not
- [x] **T-06** — batch `build-config` — only the build files no module batch already claims
- [x] **T-07** — batch `migration-bundle` (1,413 / 126)
- [x] **T-08** — batch `web-src` (2,324 / 337)
- [x] **T-08b** — lex Angular **inline** `template:`/`styles:` literals as HTML; make `@mocked:` a survivor that `--check` **enumerates**, so the PR's mocked-seam list is generated from code
- [x] **T-08c** — judge pass over the 40 above-threshold entries T-08b stripped without one; closes web-src's `aboveThresholdUnreviewed` to 0
- [x] **T-09** — batch `server-test` (3,795 / 319) — manager-dispatched judges over 10 pre-computed shards, in waves of 4, so a session death costs one shard
- [x] **T-10** — batch `server-main` (5,360 / 787)
- [x] **T-11** — batch `web-e2e` + `e2e/` + `web/scripts` (6,736 / 149 — densest)
- [x] **T-11b** — `strip.mjs` dropped a line from some multi-line `//` runs, blinding the detector T-15 wires into CI; bisect, fix, re-`--check` tree-wide
- [x] **T-12** — batch `migrations-sql` (1,946 / 58) — Flyway checksums change here
- [x] **T-13** — `flyway repair` the LAN Postgres; backend boots, `flyway validate` passes, whole server suite green against the repaired history
- [x] **T-14** — serial rename pass: collect every `RENAME:` marker, dedup, apply in ~20-groups, regenerate the OpenAPI snapshot + orval client in the SAME commit
- [x] **T-15** — wire `--check` into `ci.yml` (every push, no path filter) + a `preflight.sh` stage — LAST, so the gate lands green; also correct the skill's batch table from what shipping taught
- [x] **T-16** — re-tag the landing proof video `journey:J-31` in `public-routes.spec.ts`; verify the deployed preview bookmark renders it
- [x] **T-18** — the `extract` gate was red on a **pre-existing** break: fix the stale `PersonClub` assertion AND add `tenant-rules.yaml` to `extract.yml`'s path filter so the gate covers its own input
- [x] **T-17** — §4 gate: local real-idp green, then the PR's own checks job-level green on the merge head

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

**Batch `migrations-sql` is IN scope** (operator, 2026-08-14 — chose strip-and-repair over skip).
Stripping the 58 applied migrations changes every checksum, so `flyway validate` fails on the LAN
Postgres this box runs all ITs against, and on every other dev box. `clean-disabled: true` means no
easy recreate — hence the repair recipe below.

**Measured batch sizes** — drove the sharding, not guesswork:

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

## Outcome

PR #249, green on its own `pull_request` checks. **21,031 comments across 1,799 files** removed
from `alpenflight/` + `e2e/`; tree-wide `strip.mjs --check alpenflight e2e` exits **0** — no prose
comments, no leftover `RENAME:` markers. The check is a step in the graph-root `changes` job,
**before any path filter**, so a docs-only or workflow-only delta still executes it. Using the
stripper found **7 defects in the stripper** (symlink walk, per-line manifest scoring, heredoc
bodies, Angular inline templates, dropped multi-line `//` lines, module-root config files,
`@mocked:` governance), each fixed before the batch that needed it.

**Flyway repair — every other dev box needs this** after pulling the stripped migrations. Never
`flywayClean`: `clean-disabled: true` is deliberate and the LAN DB is shared.

```
cd alpenflight/server
source ~/.bashrc          # DATASOURCE_URL / _USER / _PASSWORD
./gradlew flywayRepair    # rewrites flyway_schema_history checksums only
./gradlew flywayValidate  # "Successfully validated 58 migrations"
```

Honest gap: the comments that could NOT become names were disproportionately **invariants enforced
by an absence** — "deliberately NOT `@Transactional`", "keep `Tenants.runAs` outside the
`TransactionTemplate`". Nothing fails when a refactor removes them and no name carries them, so
they are filed as `[LOST-INVARIANTS-NEED-GUARDS]` in `_BOYSCOUT.md` for arch tests / ITs.
