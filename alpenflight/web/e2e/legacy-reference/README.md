# Legacy reference screenshots — capture once, commit, never reap

Legacy `flsweb` is **frozen** (reference-only, see root `CLAUDE.md`). Its UI never
changes again, so its parity screenshots only need to be captured **once** — and
when they live in git, the in-flight dev proof loop can pair the FRESH AlpenFlight
screens against these PERSISTENT legacy refs on **every push**, so the per-journey
proof page is always complete (videos + paired legacy↔AlpenFlight screenshots)
with NO dependency on the heavy nightly fan-out for the visual pairing.

This closes the recurring "videos OR screenshots, depending on which job deployed
last" pain (J-6 T-17): there is now exactly **one source per journey** for the
visual pairing — the legacy side from here, the AlpenFlight side from the per-push
clean-seed capture.

## Layout

```
e2e/legacy-reference/
└── <feature>/            feature folder == the per-journey screen family
    ├── list.png          the legacy list/table view (≥3 rows, every column)
    ├── form.png          the legacy edit form (full field set, populated)
    └── <view>.png        any further paired view (setup, scheduler, motor, …)
```

Filenames are the bare **view** key (`list`, `form`, `setup`, …) — the same view
the AlpenFlight side pairs against. The `<feature>` folder + view filename ARE the
pairing key the gallery generator (`generate-gallery.mjs`) uses via the per-push
`screenshots.json` sidecar: legacy `file` points at
`e2e/legacy-reference/<feature>/<view>.png`, AlpenFlight `file` points at the fresh
per-push capture, both declared under the same `journey` + `view`.

## The pattern — capture-once-and-commit (do this at T-01 / T-13)

When a journey adds (or replaces) a legacy screen:

1. The journey's **legacy parity spec** (the `*-parity-J<n>.spec.ts` driven against
   the legacy `flsweb` stack in the fan-out — reuse the existing one; only write a
   new one if none exists) captures the legacy view(s) full-page on a **populated**
   surface (≥3 rows, every column — an empty "No Data" shot proves nothing). Expand
   any legacy accordions before shooting; anchor on a unique element.
2. Commit those PNGs here under `legacy-reference/<feature>/<view>.png` **once**.
   They are fixtures: made once, in git, never reaped, intact through the whole
   journey's dev (and forever after — legacy is frozen).
3. The **per-push** proof job (`alpenflight-proof` in `ci.yml`) stages these
   committed legacy refs + the journey's fresh clean-seed AlpenFlight captures into
   the `--screenshots` dir, writes `screenshots.json` pairing them by `view`, and
   the generator renders the paired block on the per-journey page — every push.

The heavy nightly fan-out still owns the **migration round-trip proof** (the real
legacy→migrate→AlpenFlight data chain — the done-bar). That is SEPARATE from this
visual screenshot pairing; it does not need to re-capture legacy screens for the
gallery's pairing anymore (the refs are here).

## Provenance

| feature | journey | source | captured |
| --- | --- | --- | --- |
| `planning` | J-6 | legacy `flsweb` planning future-days list + one day's edit form + the `/planningsetup` wizard (`planning-parity-J6.spec.ts`) | 1280×800 full-page PNGs; list 33KB · form 44KB · setup 19KB |
| `reservations` | J-6b | legacy `flsweb` aircraft-reservation edit form (`reservations/form.png`) — for the reservation-edit hardening pairing. NOT committed (the legacy Node-8 `flsweb` + MSSQL stack is unavailable locally; the heavy chain captures it at the CI fan-out, not in T-17's local budget). | — (deferred — AlpenFlight-only until a same-surface fanout run commits it) |

When a future journey lands its legacy refs here, add a row above with its
provenance so the "captured once" lineage stays auditable.

**J-6b decision (T-17, legacy-pairing per screen):**
- **planning-edit form** → PAIRED against the committed `planning/form.png` ref
  (the field set is unchanged — only read-only/edit-mode + DD.MM.YYYY + inline
  validation are hardened on it). The real-idp sibling captures
  `alpenflight-planning-edit-form.png`; CI `add_pair` pairs the two.
- **reservation-edit form** → AlpenFlight-only for now. The legacy
  `reservations/form.png` ref could not be captured in T-17 (no local legacy
  stack); the existing legacy `reservations-parity-J5.spec.ts` shoots the legacy
  reservation edit form in the fan-out, so a future same-surface fanout run can
  commit it here once. Until then CI's `add_pair` degrades to the AlpenFlight
  side (or skips the whole pair when no AF reservation-edit shot is captured —
  the J-6b real-idp sibling captures the calendar + planning form, not a
  reservation-edit form, since seeding a pickable reservation for clubadmin1's
  clean tenant is out of T-17's one-spec budget; the reservation-edit hardening
  is fully proven in the mock inner-loop + the J-5 reservation real chain).
- **reservations Day/Week CALENDAR** → greenfield UX (no legacy parity per
  `J-6b-oracle.md` §Reservations) → AlpenFlight-only shots
  (`alpenflight-reservations-calendar-day.png` / `-week.png`), never paired.
