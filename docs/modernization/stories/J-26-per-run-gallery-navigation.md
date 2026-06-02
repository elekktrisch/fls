---
id: J-26
title: Per-run gallery navigation + heavy-chain pre-merge preview deploy (infra)
epic: E-13
status: ready
journey0: false
carved: false
depends_on: [J-24, J-25]
rolls_up: []
---

# J-26 — Per-run gallery navigation + heavy-chain pre-merge preview deploy

**Why (J-0c evidence).** The fan-out proof chain lives in `alpenflight-proof-fanout.yml`,
the only workflow that runs the real legacy stack and records the real legacy-create
video + AlpenFlight fan-out videos. Its gh-pages deploy is **main-gated**, and the
per-PR `ci.yml` preview uses a 16-byte **stub** legacy video (no legacy stack in CI) —
so pre-merge there was **no clickable J-0c fan-out gallery**, and the top
`/alpenflight/proof/index.html` had no navigation to any per-run gallery. During J-0c
this was worked around by **manually** publishing the green run's gallery to
`/alpenflight/proof/j-0c-fanout/` and hand-injecting a nav link into the top index — a
link the next main deploy will regenerate away (the `j-0c-fanout/` folder survives via
`keep_files: true`, just unlinked).

**Scope.**
- Teach the gallery generator (`alpenflight/web/e2e/proof-gallery/generate-gallery.mjs`)
  to always emit a **"Per-run proof galleries"** nav block listing the sibling
  per-run/per-journey galleries (discover `alpenflight/proof/*/` subdirs +
  `alpenflight/proof-preview/*/`), so navigation survives every regeneration. Each
  per-run gallery links back to the top index (already does).
- Add a **pre-merge preview deploy** to `alpenflight-proof-fanout.yml`: when the chain is
  green, publish the real fan-out gallery to a stable namespaced subpath
  (`alpenflight/proof/j-0c-fanout/` or `alpenflight/proof-preview/<ref>/`), `keep_files:
  true`, NOT main-gated — so heavy-chain proofs are clickable pre-merge without a manual
  push. Needs a trigger reachable off-main (the temp branch-push trigger pattern, or
  `workflow_dispatch` once the file is on main).
- Decide + document the main-deploy interaction so one workflow's `index.html` does not
  silently replace another's journey sections (per-run is fine; the **nav** is the
  contract). Galleries stay per-run; the top index is the navigable hub.

**Proof.** Push a branch whose fanout run is green → the fan-out gallery is reachable
from the live top `/alpenflight/proof/` nav **without** a manual gh-pages commit, and the
link still resolves after a subsequent main deploy regenerates the top index.

**Not in scope.** Aggregating multiple journeys into one index (operator: per-run is
fine). Only navigation + pre-merge publish.
