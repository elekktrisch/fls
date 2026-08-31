---
title: 'Sprint Change Proposal — the client component library'
date: '2026-08-31'
status: 'approved'
mode: 'incremental'
trigger_story: '1.5-the-record-list-and-the-list-toolbar'
---

# Sprint Change Proposal: the client component library

## 1. Issue Summary

Story 1.5 ("The record list and the list toolbar") is still `backlog` — no code exists. Its frozen
intent bans "a CSS/component library beyond custom properties," meaning every form control in
`client/platform`, including the date picker and typeahead that Epic 3's flight form needs, would
be hand-rolled from raw HTML and CSS.

Roman raised this before story 1.5 starts: attempt 1 used `ng-zorro-antd` (Angular's Ant Design
port) successfully, at the cost of roughly 150 lines of `!important` overrides in `styles.css` to
strip corner radius and retune control heights for its own `DESIGN.md`-equivalent look. He wants
rebuild 2 to make the same trade deliberately, before ~45 thin slices and the flight form's date
field and typeahead compound the cost of building everything by hand.

Two decisions followed from the discussion:

- **The component library.** Weighed `ng-zorro-antd` against PrimeNG (both MIT, both Angular
  22-matched as of 2026-08-31: `ng-zorro-antd` `22.0.1` published 2026-08-07, `primeng` `22.1.0`
  published 2026-08-18). `ng-zorro-antd`'s cost is *known* — attempt 1 already paid the light-theme
  override tax. Its dark-theme cost is not known, because AlpenFlight is dark-only (NFR-8) and
  attempt 1 was not. Roman chose `ng-zorro-antd`, conditional on a spike pricing the dark-mode cost
  before it binds Epic 3.
- **The theming layer.** Roman is used to Tailwind, and attempt 1 combined Tailwind with
  `ng-zorro-antd` successfully (Tailwind tokens authoritative, `ng-zorro-antd`'s `--ant-*` variables
  consume). Story 1.4 ("The application shell and the design tokens") already shipped — status
  `done`, all tasks checked — with a plain `:root` custom-property token block and no Tailwind. That
  story reopens as part of this proposal.

## 2. Impact Analysis

**Epic impact:** none. Epic 1 already lists "the client platform: the design tokens, `RecordList`
and `RecordItem`, the typeahead, the field row, the search field, the filter chips, the sort
control, and the focus ring" as an Epic-1 deliverable, with no library named. FR-18, FR-19, and
UX-DR7–UX-DR9 (typeahead, date field, time field) describe *behavior contracts* — opens on focus,
typed-and-click write the same value, never a native `<input type="date">` — not an
implementation. `epics.md` needs no change.

**Story impact:**

- **Story 1.5** (`backlog`, not started) — its frozen intent is amended: the component-library ban
  is lifted, `ng-zorro-antd` is added, `SearchField` becomes a themed wrapper over `nz-input`, and a
  bounded `/dev` spike prices the dark-mode override cost for `nz-select` and `nz-date-picker`
  before Epic 3 commits.
- **Story 1.4** (`done`, all code shipped) — reopens to add Tailwind v4's `@theme` token pipeline
  in place of the plain `:root` block. The amendment is additive only: `@theme` emits the same
  custom-property names already in use, so `shell.css`, `destination-placeholder`, and `home` need
  no code change. Status reverts to `in-progress`; `review_loop_iteration` bumps to 1.

**Architecture impact:** one new architecture decision, `AD-23`, added to `ARCHITECTURE-SPINE.md`
— `ng-zorro-antd` is the base for every form control with a library match; `RecordList`,
`RecordItem`, and a list's filter chip stay hand-written, per `AD-17`. Marked **provisional**,
matching the pattern `CLAUDE.md` itself used for the two primary directives before
`bmad-architecture` ratified them: ratification is conditional on story 1.5's dark-mode spike
finding the cost attempt-1-sized, not materially larger.

**Other artifacts:** `project-context.md` gains a stack line and a pitfall entry recording the
dark-mode cost is unproven. `sprint-status.yaml`'s `1-4` entry reverts from `review` to
`in-progress`; no epic added, removed, or reordered, so no other entry changes.

**PRD/MVP impact:** none. No requirement changes; this is an implementation decision inside
requirements that already existed.

## 3. Recommended Approach

**Selected: Option 1, Direct Adjustment**, spanning two stories. Effort: Low–Medium. Risk:
Low–Medium.

- Story 1.5 hasn't started, so amending its frozen intent before development costs nothing beyond
  the amendment itself.
- Story 1.4 is code-complete but the amendment is additive (same custom-property names), so the
  regression surface is small and the manual check (pixel-identical shell render) catches a
  divergence directly.
- The main residual risk — the dark-mode override cost — is not resolved by this proposal. It is
  *contained*: priced by a spike inside story 1.5, before `AD-23` ratifies and before Epic 3 commits
  the typeahead and date field to the same library. Rollback rejected: no code exists yet in either
  story that a rollback would simplify undoing.

## 4. Detailed Change Proposals

### `_bmad-output/implementation-artifacts/spec-1-5-the-record-list-and-the-list-toolbar.md`

- **Approach:** adds `ng-zorro-antd` `22.0.1` as the base component library; `SearchField` wraps
  `nz-input`; `RecordItem`/`RecordList`/`ListGroupHeader` stay hand-written with Tailwind utilities
  where they read clearer than custom CSS; adds the `/dev/component-spike` dark-mode spike for
  `nz-select` and `nz-date-picker`.
- **Boundaries:** the "Never" ban on a component library is replaced with a ban on a *second*
  theming mechanism beside Tailwind's `@theme` pipeline and the `--ant-*` bridge, and a ban on a raw
  `ant-*` class reaching outside its wrapper.
- **Code Map / Tasks:** adds `package.json` (`ng-zorro-antd`), the `styles.css` dark bridge, and the
  `/dev/component-spike` route and component.
- **Acceptance Criteria / Design Notes / Verification:** adds a criterion and a manual check for the
  spike's zero-radius, no-light-leakage result; a design note ties the spike's finding to `AD-23`'s
  ratification.

### `_bmad-output/implementation-artifacts/spec-1-4-the-application-shell-and-the-design-tokens.md`

- **Frontmatter:** `status: done` → `in-progress`; `review_loop_iteration: 0` → `1`.
- **Intent:** an amendment paragraph explains the reopening and links it to this proposal.
- **Boundaries:** clarifies Tailwind is a build/utility layer, not the component-library decision
  (that stays story 1.5's), and bans rewriting already-reviewed component CSS into utilities this
  story.
- **Code Map / Tasks:** adds `package.json` (`tailwindcss`, `@tailwindcss/postcss`),
  `.postcssrc.json`, and the `styles.css` restructure from `:root { ... }` to
  `@import 'tailwindcss'; @theme { ... }` under identical custom-property names.
- **Acceptance Criteria / Verification:** adds a criterion and a manual check proving the shell
  renders pixel-identical after the amendment — the pipeline changes how tokens publish, never
  their values.

### `_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md`

- Adds **AD-23 (provisional)**: `ng-zorro-antd` is the base for every form control with a library
  match, themed through the `--ant-*` bridge built on the library's own dark stylesheet, never
  Tailwind or a second CSS mechanism reaching into a library control directly. `RecordList`,
  `RecordItem`, and a list's filter chip stay hand-written per `AD-17`. Binds UX-DR6–UX-DR17,
  FR-18, FR-19, FR-60, FR-61. Ratification is conditional on story 1.5's spike.

### `project-context.md`

- New trap: `ng-zorro-antd`'s dark-theme cost is unproven, unlike its light-theme cost — read the
  story 1.5 spike's finding before assuming the light-theme number applies.
- "The stack" line gains `ng-zorro-antd` (provisional, `AD-23`) and the Tailwind `@theme` pipeline,
  and names which components stay hand-written and why.

### `_bmad-output/implementation-artifacts/sprint-status.yaml`

- `1-4-the-application-shell-and-the-design-tokens`: `review` → `in-progress`.

## 5. Implementation Handoff

**Scope: Moderate.** Two implementation-artifact stories change (one reopened, one still
pre-development), plus one architecture-spine addition and one durable-context update. No epic,
PRD, or MVP change; no rollback.

- **Developer agent:** implements story 1.4's amendment first (Tailwind pipeline, additive,
  low-risk regression check available), then story 1.5 in full, including the `/dev/component-spike`
  and its checkpoint flag.
- **Product Owner / Developer coordination:** the checkpoint flag from story 1.5's spike decides
  whether `AD-23` ratifies as written or gets revisited — that decision point should reach Roman
  before Epic 3's typeahead/date-field stories are drafted.

**Success criteria:** story 1.4's shell renders pixel-identical after the amendment; story 1.5 ships
`RecordList`/`RecordItem`/`ListToolbar` with `nz-input`-backed search, and the `/dev/component-spike`
reports a dark-mode override cost that is either attempt-1-sized (ratify `AD-23` as written) or
materially larger (revisit before Epic 3).
