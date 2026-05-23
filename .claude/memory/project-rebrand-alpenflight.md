---
name: project-rebrand-alpenflight
description: FLS rebrand selected — AlpenFlight (alpenflight.ch); DACH-coded compound; no TM filing; domain registration pending
metadata: 
  node_type: memory
  type: project
  originSessionId: f5a82071-f81e-4c45-b21c-3adc7088e170
---

Picked **AlpenFlight** as the rebrand for FLS, with `alpenflight.ch` as the canonical domain (chosen over `.aero` purely on cost — `.ch` is ~CHF 12/yr vs `.aero` ~CHF 80/yr; the aviation-TLD signal wasn't worth 7× the price). Decision date: 2026-05-16. **Status: name selected, domain not yet registered, no TM filing — confirm with user before assuming any of these are done.**

**Why:** Original "FLS" / "Flight Logging System" lost because (a) `fls.ch` already taken, (b) "FLS" is forgettable as a 3-letter acronym. Operator wanted full rebrand bundled with the modernization cutover. Chose AlpenFlight over runner-up FlyCumulus on positioning: DACH-coded (the actual market — 12 Swiss clubs + DACH growth), identical DE/EN pronunciation, regional-brand moat fits a solo operator's local strategy, mountain logo more distinctive than crowded cloud iconography.

**Hard constraints that drove the decision:**
- DACH-first, English-friendly market scope
- DE/EN identical pronunciation (eliminated Vario, Pitot, Aileron, Bise)
- Compound base for TM safety without Markenanwalt (CHF 500–1500 declined). Vario eliminated due to Vario-Software GmbH (DE class-42 incumbent); Bise eliminated due to unresolved `bise.de` + `bise-sa.ch` unknowns
- No TM filing — just a domain
- Warm emotional valence (operator explicitly disliked Bise's cold-wind associations)

**Possible backronym (not committed):** ALPEN = Airfield · Logbook · Planning · Engine · Notifications. Likely drop — AlpenFlight is self-explanatory enough.

**How to apply:**
- When generating product/marketing copy: use "AlpenFlight" not "FLS"
- When suggesting repos, emails, URLs: default to `alpenflight.aero` (and `alpenflight.ch` as Swiss-flag if asked)
- When asked about rebrand status: confirm name picked but flag that domain registration may not have happened yet — verify with user
- TM filing was deliberately deferred — don't push Markenanwalt unless growth or external visibility changes meaningfully
- This is the rebrand for `next/` (the rewrite). Legacy `flsserver/` + `flsweb/` keep their names (read-only reference per [[fls-modernization-workflow]] and CLAUDE.md)
- An ADR was suggested at `docs/modernization/adrs/0021-rebrand-to-alpenflight.md` to capture this in the modernization workflow — check whether it's been written before re-deriving the reasoning
