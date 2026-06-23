# Proof gallery — one stable-bookmark page

Build-time tooling that turns the `real-idp` proof run's pass-videos into a
glanceable, published page: one captioned `<video controls>` per proof, stating
the assertion it proves — not an opaque `page@<hash>.webm`.

The gallery is **ONE stable-bookmark `index.html` rendering only the in-flight
journey** (the journey-under-work). There is no all-journeys index, no
per-journey history, no per-context sub-path split — merged journeys' proof lives
in their PRs. The operator bookmarks a single URL and always sees what is
currently in flight.

- `generate-gallery.mjs` — the generator. Reads a Playwright **JSON reporter**
  report, renders the journey-under-work's proof (its pass-video(s) + paired
  legacy↔AlpenFlight screenshots + the maintainability panel) into a single
  `proof/index.html` (`<h1>{journey} — proof</h1>`), and **exits non-zero** on a
  broken caption↔asset binding (AC5 link-check). Pass `--journey-under-work J-N`
  and `--out <dir>`.
- `gallery-deployed-link-check.spec.ts` (in `e2e/tests/proof-gallery/`) — the
  DEPLOYED bookmark DoD. Browserless (request-only); asserts the live bookmark +
  every declared asset resolve 200 and — when `GALLERY_DEPLOYED_JOURNEY` is set —
  that the page is the in-flight journey's page, not a thin placeholder. Runs
  under the `proof-gallery-links` Playwright project; the CI deploy steps set
  `GALLERY_DEPLOYED_URL` / `GALLERY_DEPLOYED_JOURNEY` post-deploy.
- `fixtures/` — a committed stand-in proof-output set so the generator's unit
  spec (`generate-gallery.spec.ts`) can run with no backend.

Run it: `pnpm proof:gallery` (from `alpenflight/web/`), or import
`generateGallery()` from `generate-gallery.mjs`.

## The manifest format (the contract — T-03 MUST conform to this)

There is **no bespoke manifest format**. The "manifest" the generator consumes
**is the Playwright JSON reporter output** (`['json', { outputFile: '…' }]`).
T-03 wires that reporter onto the `real-idp` project and authors a `proofVideo()`
helper that attaches the video + pushes the annotations below; nothing else needs
to produce this JSON.

The generator reads exactly these fields out of the JSON report (everything else
is ignored, so the report can carry its full Playwright payload unchanged):

```jsonc
{
  "suites": [
    {
      "specs": [
        {
          "title": "club-B is denied club-A's Location (cross-tenant 404)",
          "tests": [
            {
              // result-level: outcome + attachments
              "results": [
                {
                  "status": "passed", // only "passed" tests are published
                  "attachments": [
                    {
                      "name": "proof-video", // REQUIRED name — how the generator finds the video
                      "contentType": "video/webm",
                      "path": "videos/j0-cross-tenant-404.webm", // path to the .webm (resolved relative to the report file)
                    },
                  ],
                },
              ],
              // test-level: the caption + chips (Playwright annotations)
              "annotations": [
                {
                  "type": "proof-caption",
                  "description": "club-B is denied club-A's Location — cross-tenant GET returns 404, not 403",
                },
                { "type": "proof-ac-tag", "description": "key-error" }, // → [key-error] chip; happy | edge | key-error
                { "type": "proof-journey", "description": "J-0" }, // groups the proof under journey J-0
              ],
            },
          ],
        },
      ],
      "suites": [], // nested suites are walked recursively
    },
  ],
}
```

Field rules the generator enforces:

- A proof is **published** only if its test `status` is `passed` AND it carries a
  `proof-video` attachment with a `.webm` path.
- `proof-caption` is **required** for every published video. A `.webm` with no
  caption → the generator exits non-zero (AC5).
- The `.webm` named by a `proof-caption`/`proof-video` pair **must exist on disk**
  (resolved relative to the report file). A missing file → non-zero exit (AC5).
- `proof-journey` groups proofs by journey. If absent, the generator derives the
  journey id from the spec file path (`…/real-idp/<jN>-….spec.ts`) as a fallback.
- `proof-ac-tag` renders as the `[happy]/[edge]/[key-error]` chip; absent → no chip.

## Legacy parity videos (J-0c+) — the declared sidecar source

The Playwright JSON report only carries AlpenFlight `real-idp` proofs. A **legacy**
parity video (e.g. the legacy `flsweb` create flow J-0c records) has no manifest
entry — it's not an AlpenFlight test. So the generator takes a second, optional
source: a `legacy-video.json` **sidecar** in a `--legacy-video <dir>` directory,
declaring legacy videos keyed to a journey:

```jsonc
{
  "videos": [
    {
      "journey": "J-0c", // groups under the SAME journey section as the AlpenFlight proof
      "file": "locations-fanout-J0c.webm", // resolved relative to the sidecar dir; must exist on disk (AC5)
      "acTag": "happy", // optional chip
      "caption": "Legacy flsweb: Location created + set as homebase on 2 clubs", // REQUIRED (AC5)
    },
  ],
}
```

The legacy video renders **first** within its journey section (so a reviewer reads
legacy → AlpenFlight left-to-right), labelled `legacy parity` (CSS `.legacy-proof`).
Same AC5 link-check as AlpenFlight proofs: a missing caption or a `.webm` not on
disk fails the generator non-zero. A missing dir / sidecar is a silent no-op (no
legacy video that run). `pnpm proof:gallery` defaults `--legacy-video` to
`fixtures/legacy-video/`; the J-0c proof workflow stages the recorded legacy
`.webm` + writes the sidecar (with the run's random Location name) before invoking
the generator.

## Parity screenshots (J-1+) — the declared sidecar source

Field-by-field parity is easier to eyeball as **still images** than scrubbing two
videos. So the generator takes a third, optional source: a `screenshots.json`
**sidecar** in a `--screenshots <dir>` directory, declaring legacy + AlpenFlight
PNGs keyed to a journey + `side` + `view` so the generator can PAIR them:

```jsonc
{
  "screenshots": [
    {
      "journey": "J-1", // groups under the journey section
      "side": "legacy", // legacy | alpenflight
      "view": "list", // the pairing key (list | form | …)
      "file": "legacy-aircraft-list.png", // relative to the sidecar dir; must exist (AC5)
      "caption": "Legacy flsweb: the aircraft list", // REQUIRED (AC5)
    },
    {
      "journey": "J-1",
      "side": "alpenflight",
      "view": "list",
      "file": "alpenflight-aircraft-list.png",
      "caption": "AlpenFlight: the /aircraft list",
    },
    // … legacy/alpenflight × list/form = 4 entries for J-1
  ],
}
```

Per journey, a **parity-screenshots block** renders one row per `view`; within a
row the `legacy` `<img>` is forced LEFT, the `alpenflight` `<img>` RIGHT (the same
left-to-right framing as the videos). Each `<img>` links to the full-size PNG.
Same AC5 link-check: a missing caption or a `.png` not on disk fails the generator
non-zero. A missing dir / sidecar is a silent no-op. `pnpm proof:gallery` defaults
`--screenshots` to `fixtures/screenshots/`; the fan-out workflow stages the four
fullPage PNGs the parity specs capture (legacy `aircrafts-parity-J1.spec.ts` +
AlpenFlight `aircraft-migration-parity.spec.ts`) and writes the sidecar before
invoking the generator. The PNGs are diagnostic captures, NOT visual-regression —
the specs' data-testid assertions stay the real check (CLAUDE.md §8).

## The in-flight journey + pending state

The page renders only the journey passed via `--journey-under-work` (CI derives it
off the `integration/J-NNN` branch). When that journey has no green proof in the
report — e.g. a baseline run, or its real-idp spec is not yet thickened — the page
renders a **"pending"** placeholder rather than a link or a 404. The deployed
link-check's `[deployed-journey]` case rejects a thin/pending page once a real
journey-under-work is declared, so a green generator can't ship a missing page.
