---
id: J-24
title: Proof-video gallery — clickable gh-page explaining each pass-video
epic: E-13
status: in_progress
started_at: 2026-06-01
journey0: false
carved: true
depends_on: [J-0]
rolls_up: []
acceptance:
  - A static proof gallery (published to gh-pages under `/alpenflight/proof/`) lists each shipped journey's proof pass-videos, each with a human-readable caption stating WHAT IT PROVES (the assertion), not an opaque `page@<hash>.webm`. [happy]
  - Each video on the page is clickable and plays inline (`<video controls>` / links to the `.webm`). [happy]
  - The page is regenerated when a journey's proof gate runs green and lands on the merge line (new run → fresh videos + captions). [happy]
  - A journey listed in the roadmap with no green proof yet shows as "pending", not a broken link or a 404. [edge]
  - The gallery build fails red if a published video has no caption, or a manifest caption references a `.webm` that is not present in the proof output. [key-error]
screen: gh-pages proof gallery (`/alpenflight/proof/`, CI-published static page) — not an SPA route
headless_pulled_in: the `alpenflight-proof` CI job's video artifacts → captioned + published
migration: N/A — greenfield CI tooling
parity_test: alpenflight/web/e2e/tests/proof-gallery/proof-gallery.spec.ts (chromium project — loads the generated gallery against a fixture proof-output set; asserts captions present, every linked video resolves, pending journeys render not-broken)
adr_refs: []
---

## Tasks

Ordered, one seam each. Workers commit directly to `integration/J-24`.

- [ ] **T-01 — Gallery generator + committed fixtures (defines the manifest format).**
  New build-time Node generator `alpenflight/web/e2e/proof-gallery/generate-gallery.mjs`:
  reads a Playwright JSON report (the `['json']` reporter output) + its
  `proof-caption`/`proof-ac-tag`/`proof-journey` annotations + `proof-video`
  attachments, plus the roadmap journey IDs (static list or parsed from
  `_ORDER.md`), and emits `proof/index.html` — one captioned `<video controls>`
  per proof, "pending" rows for roadmap journeys with no green proof — reusing the
  `.github/pages/alpenflight-index.html` look. **Exits non-zero** if a published
  video lacks a caption or a caption references a `.webm` not present (the AC5
  link-check). Commit a fixture set under `e2e/proof-gallery/fixtures/`: a trimmed
  Playwright JSON report + dummy `.webm`s — one green journey (J-0, 3 captioned
  videos) + one pending journey. Add the `proof:gallery` script to `package.json`.
  *Manifest format = the Playwright JSON reporter schema; pin it here so T-03 conforms.*

- [ ] **T-02 — Gate spec `proof-gallery.spec.ts` (the journey's green bar).**
  New `alpenflight/web/e2e/tests/proof-gallery/proof-gallery.spec.ts` (chromium /
  mock lane — no backend). Runs the T-01 generator against the committed fixtures
  into a temp dir, loads `proof/index.html`, asserts: every fixture video has a
  non-empty, non-slug-hash caption; every `<video>`/link `src` resolves (file
  exists / 200); the pending journey renders a "pending" marker, not a broken link;
  writes screenshots per §8. Depends on T-01.

- [ ] **T-03 — Real manifest emission: `proofVideo()` helper + JSON reporter + J-0 retrofit.**
  New `alpenflight/web/e2e/tests/real-idp/_helpers/proof-video.ts` exporting
  `proofVideo(page, testInfo, { journey, caption, acTag })` — in the test's
  `finally` AFTER `ctx.close()`, resolve `page.video()?.path()`, `testInfo.attach`
  the `.webm` + push `proof-caption`/`proof-ac-tag`/`proof-journey` annotations.
  Add `['json', { outputFile: 'proof-manifest.json' }]` to the `real-idp` run's CI
  reporter list in `playwright.config.ts`. Retrofit the 3 tests in
  `locations-crud-tenant-isolation.spec.ts` to call it. Depends on T-01 (format).

- [ ] **T-04 — Publish wiring (every run builds + link-checks; main publishes).**
  In `.github/workflows/ci.yml` `alpenflight-proof` job: add an `always()` generate
  step that runs the generator over `test-results` + `proof-manifest.json` →
  `public/alpenflight/proof/` (link-check fails the run red on a missing
  caption/video), upload it in the existing proof artifact; add a
  `main`-gated gh-pages deploy of `public/` (grant the job `permissions: contents:
  write`) — or hand `proof/` to `alpenflight-e2e.yml`'s existing main deploy. Add a
  "Proof gallery" card/link to `.github/pages/alpenflight-index.html` Reports
  section. Depends on T-01 + T-03.

## Context (why — operator ask, /do-retro 2026-06-01)

J-0 shipped 3 pass-videos as the acceptance artifact, but they land only inside
the per-run CI artifact `alpenflight-proof-<runid>` as raw
`web/test-results/locations-crud-tenant-isolation-*/.webm` — you must download +
unzip + guess which `.webm` is which (the spec drives its own `recordVideo`
context, so the file is keyed by test outputDir, not a readable name). The
operator wants the proof **glanceable**: a clickable published page where each
video is captioned with the assertion it proves (e.g. "J-0 · cross-tenant 404 ·
club-B is denied club-A's Location"). This makes the pass-video acceptance
artifact actually reviewable — the human-parity half of the do-suite done-bar —
without an archaeology dig.

## Spec must assert (the contract)

The gallery generator produces a static page (`public/alpenflight/proof/index.html`,
linked from the existing `alpenflight/index.html` Reports section) that, per
shipped journey, renders each proof pass-video with a caption naming the assertion
it proves and its `[happy]/[key-error]/[edge]` tag. A roadmap journey with no
green proof shows "pending", not a 404. The generator step **fails red** if a
published video has no caption or a manifest caption references a missing `.webm`
— so the gallery can't silently rot.

J-24's own green gate is `proof-gallery.spec.ts` (chromium / mock lane — no
backend, no Keycloak): it runs the generator against a committed **fixture
proof-output set** (a stand-in for `test-results/` + the JSON manifest holding one
green journey and one pending journey), then loads the generated
`proof/index.html` and asserts: every fixture video has a non-empty,
non-slug-hash caption; every `<video>`/link `src` resolves (file exists / 200);
the pending journey renders a "pending" marker, not a broken link. One green
Playwright run proves the whole journey — no real-idp dependency.

## Notes (carve decisions + shape)

**Branch base (for /do-ship): cut `integration/J-24` from `integration/J-0`, NOT `main`.**
J-0 is merged into the `integration/J-0` line, not yet into `main`. Everything
J-24 extends — `ci.yml`'s `alpenflight-proof` job, the `real-idp` project's
`video: 'on'` in `playwright.config.ts`, and `locations-crud-tenant-isolation.spec.ts`
(the spec that gets the `proofVideo()` retrofit) — lives on `integration/J-0`, not
on `main`. Branching off `main` would put J-24 on a tree with no proof pipeline to
hang the gallery off. (`integration/J-0` already contains all of `integration/migration`.)

**Reuse the existing pipeline, don't invent one.** gh-pages publishing already
exists in `.github/workflows/alpenflight-e2e.yml`: it assembles `public/`, copies
`alpenflight/web/playwright-report` → `public/alpenflight/report`, templates
`.github/pages/alpenflight-index.html`, deploys via `peaceiris/actions-gh-pages@v4`
to `gh-pages` with `keep_files: true`, **gated `if: github.ref == 'refs/heads/main'`**
(branches surface outputs as a workflow artifact only). The proof videos come from
the `alpenflight-proof` job in `.github/workflows/ci.yml` (artifact
`alpenflight-proof-<runid>`, paths `alpenflight/web/test-results` +
`playwright-report`; `video: 'on'` on the `real-idp` project per
`playwright.config.ts`). NOTE the proof spec creates its own
`browser.newContext({ recordVideo: { dir: testInfo.outputDir } })`, so videos are
NOT auto-attached to Playwright's report — caption↔video binding must be done at
the spec site (below).

### Carve decision 1 — caption source: spec-site annotation + JSON manifest

Captions are authored **at the assertion site** and serialized by a JSON reporter
— so they're maintained-with-the-spec (rename/add/remove a test ⇒ manifest
updates automatically; no hand-curated list to drift).

- Add a tiny `proofVideo(testInfo, { caption, acTag })` helper (e2e `_helpers/`).
  In each proof test's `finally` (after `await ctx.close()` finalizes the
  `.webm`): resolve `await page.video()?.path()`, then
  `testInfo.attach('proof-video', { path, contentType: 'video/webm' })` +
  `testInfo.annotations.push({ type: 'proof-caption', description: caption })` +
  one for `proof-ac-tag`. (Attach AFTER close — the file isn't flushed until the
  context closes.)
- Add a `['json', { outputFile: '…/proof-manifest.json' }]` reporter to the
  `real-idp` project's run. It records, per test: title, status, the
  `proof-video` attachment path, and the `proof-caption`/`proof-ac-tag`
  annotations → that JSON **is** the manifest the generator consumes.
- The journey id is derivable from the spec file path / a `proof-journey`
  annotation; the `[tag]` chip comes from `proof-ac-tag`. The human caption stays
  free-text at the assertion (a sentence, not a slug).

Rejected alternative: derive captions from this journey file's `acceptance:`
lines. Cleaner-looking but drifts — AC lines and the actual test set diverge over
time, and there's no per-`.webm` binding. The requirement is captions tied to the
RUNNING test, not to a parallel doc.

### Carve decision 2 — publish timing: build every run, publish on merge

Resolves the stub's open question. The gallery is **generated + link-checked on
every proof run** (so a missing video / absent caption fails the run RED
pre-merge, and the assembled gallery is downloadable as the proof job's workflow
artifact for a PR reviewer), but **deployed to gh-pages only on merge to `main`**.

- Co-locate generation in `ci.yml`'s `alpenflight-proof` job (the videos +
  manifest are right there in `test-results/`): a generate step (always()) that
  produces `public/alpenflight/proof/` and runs the build/link-check assertion.
- gh-pages deploy stays where it already is — `main`-gated. Either bolt a
  `main`-gated deploy onto the proof job (needs `permissions: contents: write`,
  currently `read`) OR have `alpenflight-e2e.yml`'s existing `main` deploy pick up
  the generated `proof/` dir (cross-workflow artifact hand-off). Implementer
  picks; both keep gh-pages writes single-source-on-main.
- Rejected alternative: deploy per-PR from `ci.yml`. More immediate for
  reviewers, but N concurrent `integration/**` branches racing one gh-pages
  branch + needing write tokens on PR-triggered runs is churn the workflow-artifact
  path already covers. AC3 ("regenerated when green") is satisfied on merge; the
  red-gate (AC5) runs every PR regardless.

### Likely seams (one component each — non-binding, for /do-ship)
- *Caption manifest convention* — `proofVideo()` e2e helper + JSON reporter wiring
  on the `real-idp` project; retrofit J-0's `locations-crud-tenant-isolation.spec.ts`
  to call it (3 tests = 3 captions). One e2e-side change.
- *Gallery generator* — a build-time Node script reading proof `test-results/` +
  `proof-manifest.json` + the roadmap journey list → `proof/index.html` (reuse the
  `.github/pages/alpenflight-index.html` styling so it matches the dashboard).
  Emits the link-check assertion (non-zero exit on missing caption / missing
  `.webm`). One generator.
- *J-24 gate spec* — `proof-gallery.spec.ts` (chromium) + a committed fixture
  proof-output set (one green journey, one pending). Runs the generator, loads the
  output, asserts. One spec + fixtures.
- *Publish wiring* — generate step in `ci.yml` proof job (every run, artifact) +
  `main`-gated gh-pages deploy of `proof/` + a link from `alpenflight-index.html`.
  One workflow change (mind the `contents: write` permission if deploying from
  ci.yml).

## Assumptions made

- Scope is the **proof (real-idp) pass-videos**, not the mock-auth chromium run
  (which already publishes its Playwright HTML report). The gallery is the curated
  "what each green proves" view, complementary to the raw HTML report.
- gh-pages (`/alpenflight/proof/` under the existing namespace) is the publish
  target — no new hosting.
- The "shipped journeys" list the gallery iterates = the roadmap (`_ORDER.md`)
  journey IDs; a journey with no green proof manifest entry yet renders "pending".
  Today that's J-0 green, the rest pending — the gallery grows as journeys ship.
- J-24 is an accepted **infra journey** (not an SPA screen) — its "one green
  Playwright run" is the chromium gate spec against the generated gallery, not a
  Keycloak real-chain run. depends_on J-0 only for a real `.webm` to caption; the
  gate itself uses fixtures, so J-24 can be built before any further journey ships.
</content>
</invoke>
