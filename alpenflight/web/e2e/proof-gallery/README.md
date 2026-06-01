# Proof-video gallery (J-24)

Build-time tooling that turns the `real-idp` proof run's pass-videos into a
glanceable, published gallery: one captioned `<video controls>` per proof,
stating the assertion it proves — not an opaque `page@<hash>.webm`.

- `generate-gallery.mjs` — the generator. Reads a Playwright **JSON reporter**
  report + a roadmap journey list, emits `proof/index.html`, and **exits
  non-zero** on a broken caption↔video binding (AC5 link-check).
- `fixtures/` — a committed stand-in proof-output set (one GREEN journey, one
  PENDING) so J-24's own gate spec (T-02) can run the generator with no backend.

Run it: `pnpm proof:gallery` (from `alpenflight/web/`), or import
`generateGallery()` from `generate-gallery.mjs` (T-02 drives it programmatically).

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

## Roadmap / pending rows

The set of journeys the gallery iterates = the roadmap IDs in
`docs/modernization/stories/_ORDER.md` (the `| **J-N** |` table rows). Any roadmap
journey with **no green proof entry** in the report renders a **"pending"** marker
— never a link or a 404. The static fallback list lives at the top of
`generate-gallery.mjs` (`ROADMAP_FALLBACK`) for when `_ORDER.md` is not reachable
from the run dir.
