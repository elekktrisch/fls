---
id: J-25
title: Proof-gallery PR previews — clickable per-branch gallery before merge
epic: E-13
status: in_progress
started_at: 2026-06-01
journey0: false
carved: true
depends_on: [J-24]
rolls_up: []
acceptance:
  - On a `pull_request` proof run (non-`main`), the generated gallery deploys to a per-branch gh-pages subpath `alpenflight/proof-preview/<sanitized-head-ref>/` and a sticky PR comment posts the CLICKABLE preview URL — no artifact download. [happy]
  - The deployed preview URL resolves (HTTP 200) and renders that run's captioned videos (the live page contains the proof captions, not a 404 / empty page). [happy]
  - The preview re-publishes on each green proof run for the branch (sticky comment updates in place, not a new comment per run). [happy]
  - Per-branch namespacing + `keep_files: true` means a second branch's preview deploy does not clobber the first (disjoint `destination_dir`s; the canonical `/alpenflight/proof/` main deploy is untouched). [edge]
  - When the PR closes (merged or not), a reaper removes `alpenflight/proof-preview/<sanitized-head-ref>/` from gh-pages so previews don't accumulate. [key-error]
screen: gh-pages proof preview (`/alpenflight/proof-preview/<branch>/`, CI-published per PR) — not an SPA route
headless_pulled_in: the `alpenflight-proof` job's generated `public/alpenflight/proof/` → deployed per-branch pre-merge
migration: N/A — greenfield CI tooling
parity_test: post-deploy live link-check polls the preview URL → 200 + captions present (bounded retry for gh-pages propagation); J-24's `alpenflight/web/e2e/tests/proof-gallery/proof-gallery.spec.ts` stays green (generator unchanged); reaper path-derivation unit-asserted
adr_refs: []
---

## Tasks

Ordered, one seam each. Workers commit directly to `integration/J-25`. All edits are
CI/workflow (no backend/domain/frontend). Shared contract: a **`preview` step
(`id: preview`)** in the `alpenflight-proof` job emits outputs `subdir`
(`alpenflight/proof-preview/<sanitized-head-ref>`) and `url` (full
`https://elekktrisch.github.io/fls/<subdir>/`); T-02/T-03 consume them.

- [x] **T-01 — Per-PR preview publish + clickable link** (`.github/workflows/ci.yml`, `alpenflight-proof` job).
  Add: (a) a `preview` step that sanitizes `github.head_ref` (`/`→`-`, strip non-`[A-Za-z0-9._-]`) and emits `subdir` + `url`; (b) a 2nd `peaceiris/actions-gh-pages@v4` deploy gated `github.event_name == 'pull_request' && steps.gallery.outcome == 'success'`, `publish_dir: public/alpenflight/proof`, `destination_dir: ${{ steps.preview.outputs.subdir }}`, `keep_files: true`; (c) a preview-scoped `concurrency` group keyed on `github.head_ref` (cancel-in-progress); (d) a sticky PR comment (upsert by hidden marker) + `$GITHUB_STEP_SUMMARY` line carrying `steps.preview.outputs.url`. Leave the canonical `main` deploy untouched. Validate YAML. One workflow edit.

- [x] **T-02 — Live link-check gate** (`.github/workflows/ci.yml`, `alpenflight-proof` job).
  After the preview deploy, a step (PR-only) that polls `steps.preview.outputs.url` with bounded retry (gh-pages propagation, ~up to 60s) asserting **HTTP 200** AND the body contains the J-0 proof captions (reuse the caption strings the generator emits). Fails the job red if the preview never serves / lacks captions. This is J-25's provable green. Keep J-24's `proof-gallery.spec.ts` untouched. Depends on T-01.

- [x] **T-03 — Preview reaper** (new `.github/workflows/proof-preview-reap.yml`).
  On `pull_request: { types: [closed] }`: sanitize the same ref (identical derivation as T-01 — factor the rule so they can't drift), check out `gh-pages`, `git rm -r --ignore-unmatch alpenflight/proof-preview/<sanitized-ref>`, commit + push (skip cleanly if the subdir is absent). Add a small assertion of the ref→subdir derivation (a tiny test/script-level check) so AC5 isn't only exercised by a real PR-close. `permissions: contents: write`. Validate YAML. One new workflow.

- [x] **T-04 — Gate hardening (gap-hunter findings): reaper blast-radius guard + fork-PR skip.** *(appended at the gate — green is honest, but 2 cheap safety/contract gaps.)*
  (1) **Reaper guard** (`proof-preview-reap.yml`): the `case alpenflight/proof-preview/?*)` guard is bypassable in principle by a ref sanitizing to `..` (`.` is in the allowed set → `alpenflight/proof-preview/..` → `git rm -r` resolves to parent `alpenflight/`, wiping the canonical gallery). Unreachable today (git rejects `..`/`.` as branch names) but it's a destructive op on the live site — don't rely on an external invariant. Reject any sanitized ref that isn't a single safe component (no `..`, not `.`/empty; assert `^[A-Za-z0-9._-]+$` on the *component*), refuse + clean-exit otherwise. (2) **Extend the self-check** (`proof-preview-subpath-selfcheck.sh`) to assert the guard REFUSES `..`, `.`, empty, `/`-only — so the protection is CI-asserted, not theater. (3) **Fork-PR skip** (`ci.yml`): gate the preview deploy + link-check + sticky-comment on `github.event.pull_request.head.repo.fork == false` so a fork PR SKIPS (not fails-red → blocks the required gate), matching the carve's documented contract; the reaper already downgrades a 403 to a warning. (4) Fix the stale caption-file path comment (`ci.yml` ~:443 → `alpenflight/web/e2e/...`). Files: `proof-preview-reap.yml`, `proof-preview-subpath-selfcheck.sh`, `ci.yml`. Depends on T-01/T-03.

- [ ] **T-05 — CI hygiene (boyscout, operator ask): dedup PR double-runs + retry flaky registry pulls.** *(not J-25-specific; folded in at operator request.)*
  (1) **Double-run dedup:** `ci.yml` and `alpenflight-e2e.yml` both trigger on `push` AND `pull_request` for `integration/**`, so a PR'd branch runs each workflow twice on one commit (different `github.ref` → no mutual cancel). Change both to `push: { branches: [main] }` + `pull_request: { branches: [main, "integration/**"] }` (keep `workflow_dispatch`). Integration branches always carry a draft PR in this flow → `pull_request` covers them (single run); `main` keeps its post-merge push run (canonical gh-pages deploy is `main`-ref-gated, unaffected). Update the now-stale header comments (esp. `alpenflight-e2e.yml`'s "runs on push any branch" note → PR covers in-flight). Confirm the `required` aggregator + branch protection still evaluate on PRs-to-main and on main-push.
  (2) **Registry-pull retry:** the `alpenflight-proof` job's docker image steps ("Build pgAdmin + Keycloak images", "Bring up alpenflight-infra (mailpit)", "Bring up alpenflight-dev …") fail red on transient registry blips (this session: quay.io 502 + docker.io timeout → 3 reruns). Wrap them in a bounded retry (a small bash retry loop, or `nick-fields/retry`) — ~3 attempts with backoff — so a transient 5xx/timeout self-heals instead of forcing a manual rerun. Files: `.github/workflows/ci.yml`, `.github/workflows/alpenflight-e2e.yml`. Validate YAML.

## Context (why — operator ask, 2026-06-01)

J-24 ships the captioned proof gallery but publishes it **only on merge to `main`**
(publish-on-green, to avoid N integration branches racing one gh-pages branch). So a
PR reviewer can't *click* the gallery pre-merge — they must download the
`alpenflight-proof-<runid>` artifact and open `proof/index.html` locally. This journey
gives reviewers a **clickable live URL on the PR itself**, before merge — the
publish-per-PR fork J-24's carve deliberately deferred.

## Spec must assert (the contract)

On a `pull_request` proof run, the gallery deploys to a per-branch gh-pages subpath,
a sticky PR comment posts the clickable URL, and that URL resolves (200) to the
captioned gallery for the run. Concurrent branches don't clobber each other (disjoint
subpaths + `keep_files`), the canonical `/alpenflight/proof/` main deploy is untouched,
and closing the PR reaps the preview subpath. The provable green is a **post-deploy
link-check** (poll the live URL → 200 + the J-0 captions present, bounded retry for
Pages propagation) — J-24 already proved the generator/gallery via `proof-gallery.spec.ts`
(unchanged here); J-25 proves the *publish-per-PR + reap* wiring.

## Notes (carve decisions — forks resolved)

Extends J-24's wiring in `.github/workflows/ci.yml` `alpenflight-proof` job. The gallery
is already generated into `public/alpenflight/proof/` by the `id: gallery` step (J-24
T-04) and link-checked there. J-25 adds a **second, branch-namespaced deploy** for
non-`main` runs + a PR link + a reaper. The canonical main deploy (`steps.gallery.outcome
== 'success' && github.ref == 'refs/heads/main'`, `publish_dir: public`, `keep_files:
true`) stays exactly as-is.

### Decision 1 — where/how the preview deploys (race avoidance)
Reuse the **established multi-writer `keep_files: true` gh-pages pattern** (ci.yml main
→ `/alpenflight/`, nightly → `/legacy/`; now previews → `/alpenflight/proof-preview/<ref>/`).
A second `peaceiris/actions-gh-pages@v4` step in the proof job, gated
`github.event_name == 'pull_request' && steps.gallery.outcome == 'success'`, with
`publish_dir: public/alpenflight/proof`, `destination_dir: alpenflight/proof-preview/<sanitized-head-ref>`,
`keep_files: true`. Subpaths are **disjoint per branch**, so even a cross-branch concurrent
push only needs peaceiris's fetch-rebase (no content conflict). Add
`concurrency: { group: proof-preview-${{ github.head_ref }}, cancel-in-progress: true }`
scoped to the preview so a branch's *own* rapid re-pushes serialize. *Rejected:* a
dedicated `gh-pages-previews` orphan branch — more moving parts; the shared-branch +
disjoint-subpath + keep_files pattern is already proven by three existing workflows.

### Decision 2 — sanitizing the ref
`github.head_ref` (PR source branch, e.g. `integration/J-26`) → replace `/` and any
non-`[A-Za-z0-9._-]` char with `-` → `integration-J-26`. One sanitize step emitting the
subpath + the full preview URL as step outputs.

### Decision 3 — surfacing the clickable URL
**Sticky PR comment** (upsert by a hidden marker — `marocchino/sticky-pull-request-comment`
or an `actions/github-script` find-and-update) so re-runs update one comment, no spam.
PLUS write the URL to `$GITHUB_STEP_SUMMARY` (always-on, needs no PR-write token — the
fallback when comment perms are absent). Fork-PR caveat: PRs from forks can't get a
write token, so the comment + preview deploy silently skip for fork PRs — documented,
acceptable (single-repo `integration/**` flow).

### Decision 4 — cleanup (the real cost; AC5)
A new `.github/workflows/proof-preview-reap.yml` on `pull_request: { types: [closed] }`:
sanitize the same ref, then delete `alpenflight/proof-preview/<ref>/` from gh-pages
(checkout the `gh-pages` branch, `git rm -r` the subpath, commit + push — exact and
simple). *Rejected as primary:* a scheduled age-based prune — non-deterministic and
leaves previews around longer than needed; keep only as an optional backstop follow-up
if orphaned subpaths (force-deleted branches) are observed.

### Decision 5 — the green gate (infra journey, like J-24)
J-24's `proof-gallery.spec.ts` is unchanged and stays green (generator reused as-is).
J-25's new assertion is a **post-deploy link-check step**: poll the computed preview URL
with bounded retry (gh-pages serves a few seconds to ~1 min after push), assert HTTP 200
AND the body contains the J-0 proof captions (reuse the caption strings the generator
emits). Proves "published + clickable + correct content" without a live browser. The
reaper's path-derivation (ref→subpath) gets a small unit-style assertion so AC5 isn't
only exercised by a real PR-close round-trip.

### Likely seams (one each — for /do-ship)
- *Preview deploy + ref-sanitize* — branch-namespaced peaceiris step + sanitize-and-emit-URL step + the `concurrency` group, in `ci.yml`'s `alpenflight-proof` job. One workflow change.
- *PR link surface* — sticky-comment step + `$GITHUB_STEP_SUMMARY` write. One workflow step (+ tiny script if not using a marketplace action).
- *Reaper* — new `.github/workflows/proof-preview-reap.yml` on `pull_request: closed`. One new workflow.
- *Live link-check gate* — post-deploy poll-200 + caption-present assertion (small bash/node step; reuses the generator's caption strings). One gate step.

## Assumptions made

- Builds on J-24 (done, on `main`) — reuses the same generator + the
  `public/alpenflight/proof/` output unchanged; only deploy/link/reap wiring is new.
- Canonical durable gallery stays main-only at `/alpenflight/proof/`; previews are
  ephemeral, namespaced under `/alpenflight/proof-preview/<branch>/`, reaped on PR close.
- Single-repo `integration/**` flow: the proof job already has `contents: write`
  (J-24 T-04), so the default `GITHUB_TOKEN` deploys; fork PRs skip (no write token).
- "Proof run" = the `pull_request`-triggered `alpenflight-proof` job (it evaluates the
  full PR diff, so it runs for J-* PRs). Previews track that job; a PR that doesn't
  trigger it (docs-only) simply has no preview — acceptable.
</content>
