---
id: J-24
title: Proof-video gallery — clickable gh-page explaining each pass-video
epic: E-13
status: todo
journey0: false
carved: false
depends_on: [J-0]
rolls_up: []
acceptance:
  - A published GitHub Pages page lists each journey's proof pass-videos, each with a human-readable caption stating WHAT IT PROVES (the assertion), not an opaque `page@<hash>.webm`. [happy]
  - Each video on the page is clickable and plays inline (or links to the `.webm`). [happy]
  - The page is regenerated/updated when a journey's proof gate runs green (new run → fresh videos + captions). [happy]
  - A journey with no green proof yet shows as "pending", not a broken link. [edge]
screen: gh-pages proof gallery (CI-published static page) — not an SPA route
headless_pulled_in: the `alpenflight-proof` CI job's video artifacts → published + captioned
migration: N/A — greenfield CI tooling
parity_test: the gallery page builds + renders + every linked video resolves (a link-check / build assertion in the publish job)
adr_refs: []
---

## Context (why — operator ask, /do-retro 2026-06-01)

J-0 shipped 3 pass-videos as the acceptance artifact, but they land only inside
the per-run CI artifact `alpenflight-proof-<runid>` as raw
`web/test-results/real-idp-locations-crud-*/page@<hash>.webm` — you must download
+ unzip + guess which `.webm` is which. The operator wants the proof to be
**glanceable**: a clickable published page where each video is captioned with the
assertion it proves (e.g. "J-0 · cross-tenant 404 · club-B is denied club-A's
Location"). This makes the pass-video acceptance artifact actually reviewable —
the human-parity half of the do-suite done-bar — without an archaeology dig.

## Spec must assert (the contract)

The publish job produces a static page (published to gh-pages, under the existing
`/alpenflight/` namespace) that, per shipped journey, renders each proof
pass-video with a caption naming the assertion it proves; every linked video
resolves; a not-yet-green journey shows "pending" not a 404. The page-build step
fails (red) if a referenced video is missing or a caption is absent for a
published video — so the gallery can't silently rot.

## Notes (shape — non-binding, for /do-plan to carve + /do-ship to size)

**Reuse the existing pipeline, don't invent one.** gh-pages publishing already
exists in `.github/workflows/alpenflight-e2e.yml`: it assembles `public/`, copies
`alpenflight/web/playwright-report` → `public/alpenflight/report`, templates
`.github/pages/alpenflight-index.html`, and deploys via `peaceiris/actions-gh-pages@v4`
to the `gh-pages` branch. The proof videos come from the `alpenflight-proof` job
in `.github/workflows/ci.yml` (artifact `alpenflight-proof-<runid>`, paths
`alpenflight/web/test-results` + `playwright-report`, `video: 'on'` on the
`real-idp` project per `playwright.config.ts`).

**Caption source — the load-bearing design choice.** The `.webm` filenames derive
from Playwright test titles (e.g. `…-and-sees-it-in-club-A-list-real-idp`), which
already encode intent but are slug-mangled. Cleaner: have the spec attach a
caption via Playwright **test annotations / `testInfo.attach`** or a small
per-journey `proof-manifest` (journey id → [{video, caption, AC-tag}]) that the
gallery generator consumes. The implementer picks; the requirement is captions
that are MAINTAINED WITH THE SPEC (so renaming/adding a test updates the gallery),
not a hand-curated list that drifts. Consider deriving the caption from the
journey file's `acceptance:` AC lines + their `[happy]/[key-error]/[edge]` tags.

**Likely seams (one component each):**
- *Caption manifest* — a spec-side convention (annotation or `proof-manifest.json`) emitting `{journey, video, caption, ac-tag}` from the real-idp proof spec(s). One e2e-side change.
- *Gallery generator* — a small script (build-time) that reads the proof artifacts + manifest → a static `proof/index.html` gallery. One generator.
- *Publish wiring* — extend the gh-pages assemble/deploy (mind: proof runs in `ci.yml` on the integration line; the existing deploy is `alpenflight-e2e.yml` on main pushes — decide where the gallery deploys from; publishing per-journey-PR vs. on-merge is a real fork). One workflow change.

**Open question for /do-plan:** publish the gallery on every proof run (so a PR's
reviewer sees its videos pre-merge) vs. only on merge to the integration line.
Pre-merge is more useful for review but means publishing from `ci.yml`. Decide at
carve time.

## Assumptions made

- Scope is the **proof (real-idp) pass-videos**, not the mock-auth chromium run
  (which already publishes its Playwright HTML report). The gallery is the
  curated "what each green proves" view, complementary to the raw HTML report.
- gh-pages (`elekktrisch.github.io/fls/alpenflight/`) is the publish target — no
  new hosting.
