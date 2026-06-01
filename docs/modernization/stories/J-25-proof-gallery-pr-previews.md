---
id: J-25
title: Proof-gallery PR previews — clickable per-branch gallery before merge
epic: E-13
status: todo
journey0: false
carved: false
depends_on: [J-24]
rolls_up: []
acceptance:
  - On a proof run for an `integration/**` branch / PR, the generated gallery publishes to a per-branch gh-pages subpath (e.g. `/alpenflight/proof-preview/<branch>/`) and the PR surfaces a CLICKABLE link to it — no artifact download required. [happy]
  - The preview reflects that run's videos + captions (re-publishes on each green proof run for the branch). [happy]
  - Two concurrent branch previews do not clobber each other (per-branch namespacing + keep_files). [edge]
  - A merged/closed branch's preview is reaped — or there is a documented periodic prune so previews don't accumulate forever. [edge]
screen: gh-pages proof preview (`/alpenflight/proof-preview/<branch>/`, CI-published) — not an SPA route
headless_pulled_in: the `alpenflight-proof` job's generated `proof/` → published per-branch pre-merge
migration: N/A — greenfield CI tooling
parity_test: the publish job emits a resolvable preview URL for the branch (link-check); J-24's `proof-gallery.spec.ts` stays green (generator unchanged)
adr_refs: []
---

## Context (why — operator ask, 2026-06-01)

J-24 ships the captioned proof gallery but publishes it **only on merge to `main`**
(publish-on-green, to avoid N integration branches racing one gh-pages branch). So a
PR reviewer can't *click* the gallery pre-merge — they must download the
`alpenflight-proof-<runid>` artifact and open `proof/index.html` locally. The operator
wants reviewers to get a **clickable live URL on the PR itself**, before merge. This
is the fork J-24's carve deliberately deferred ("publish per-journey-PR vs on-merge").

## Spec must assert (the contract)

Each proof run on an `integration/**` branch publishes that run's generated `proof/`
to a per-branch gh-pages subpath and the PR shows a clickable link to it. The preview
refreshes on each green run; concurrent branches don't collide; merged/closed branch
previews are cleaned up (or a documented prune exists). J-24's main-only canonical
deploy and `proof-gallery.spec.ts` gate stay intact — this is additive.

## Notes (shape — non-binding, for /do-plan to carve)

**Extends J-24's publish wiring (`.github/workflows/ci.yml` `alpenflight-proof` job).**
The gallery is already generated into `public/alpenflight/proof/` on every proof run
(J-24 T-04). This journey adds a **second, branch-namespaced deploy** for non-`main`
runs.

**Carve-time decisions (the fork J-24 left open):**
- *Race avoidance* — the reason J-24 deferred this. Namespace each branch under its own
  subpath (`/alpenflight/proof-preview/<sanitized-ref>/`) + `keep_files: true` so
  branches never write the same path; add a `concurrency` group per ref. Consider a
  dedicated `gh-pages-previews` orphan branch vs. the shared `gh-pages` branch to keep
  preview churn out of the canonical site's history.
- *Permissions / fork PRs* — the proof job now has `contents: write` (J-24 T-04). PRs
  **from forks** can't get a write token; this flow is single-repo `integration/**`
  branches, so default `GITHUB_TOKEN` works — but document the fork caveat (previews
  silently skip for fork PRs).
- *Linking the URL to the PR* — a PR comment (`actions/github-script` / a sticky
  comment action) or the job summary (`$GITHUB_STEP_SUMMARY`) carrying the resolved
  preview URL. Sticky-comment avoids comment spam on re-runs.
- *Cleanup (the real cost)* — `keep_files: true` never deletes, so preview dirs
  accumulate. Options: a `pull_request: closed` trigger that deletes the branch's
  subpath; or a scheduled reaper pruning subpaths older than N days / for merged
  branches. Pick one at carve — unbounded accumulation is the failure mode.

**Likely seams (one each):**
- *Preview deploy step* — branch-namespaced `peaceiris/actions-gh-pages@v4` deploy in
  the proof job, gated to non-`main` (`github.ref != 'refs/heads/main'`), `keep_files`.
- *PR link surface* — sticky PR comment or step-summary emitting the preview URL.
- *Reaper* — `pull_request: closed` cleanup workflow (or scheduled prune).

## Assumptions made

- Builds on J-24 (done) — reuses the same generator + the `public/alpenflight/proof/`
  output; only the deploy/cleanup wiring is new. Hence `depends_on: [J-24]`.
- The canonical, durable gallery stays main-only (`/alpenflight/proof/`); previews are
  ephemeral and clearly namespaced so they're never mistaken for the published record.
</content>
