---
title: 'Story 1.5: The record list and the list toolbar'
type: 'feature'
created: '2026-08-31'
status: 'draft'
review_loop_iteration: 0
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md', '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-fls-2026-08-24/DESIGN.md', '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-fls-2026-08-24/EXPERIENCE.md']
baseline_commit: '79182d99066b23229328c68ab183a829d65875c9'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `DESIGN.md` fixes `RecordList`/`RecordItem` as the product's only list treatment, with
a fixed toolbar (search, chips, sort), but nothing builds it yet, and story 1.6 needs it.

**Approach:** Add `ng-zorro-antd` to `client/platform` as the base component library for every form
control — the choice attempt 1 made, still MIT-licensed and pinned at `22.0.1`, an exact match to
our Angular version. This story is also the first to spend a Tailwind utility class, now that story
1.4's amendment lands the `@theme` token pipeline. Build `RecordItem`, `RecordList`, and
`ListGroupHeader` hand-written, with Tailwind utilities in their templates where one reads clearer
than a custom CSS rule — `ng-zorro-antd` has no equivalent for these three, and AD-17 already fixes
their exact stacked/aligned-zone behavior. Build `SearchField` as a themed wrapper over `nz-input`;
build `FilterChip` and `SortControl` hand-written, because `ng-zorro-antd` has no matching
primitive. Compose the three into `ListToolbar`. Add the one-time dark-mode `--ant-*` bridge to
`styles.css`'s `@theme` block, built on `ng-zorro-antd.dark.css` — never the default light
stylesheet. **Spike two higher-risk controls in isolation**, `nz-select` and `nz-date-picker`, on a
`/dev` harness route never wired to a real screen this story, to price the dark-mode override cost
before Epic 3 commits the typeahead and the date field to the same library. Mount `/records` with a
static demo dataset. Story 1.6 swaps in real data.

## Boundaries & Constraints

**Always:** `RecordItem` anatomy per `DESIGN.md`: identity, meta (` · `-joined), metric
(right-aligned), marker. Phone (<768px) stacks identity/meta left, metric/marker right, no fixed
widths; pointer (≥768px) uses fixed 104px/88px zones side by side. Toolbar order is fixed: search,
chips, sort, above the first group header. Search filters as-you-type, no submit. Chips are the
only filter mechanism; an active chip shows its value + a clear control. Sort names current
field/direction, never filters. One list background, no row rule. An absent value keeps row height
and reads `not set`. A settled record colors every zone `ink-settled`; only a live metric gets
`live` color otherwise. Every toolbar control keeps the existing focus ring. Reuse `styles.css`
custom properties only; component pixel values from `DESIGN.md` (e.g. 32px chip height) are
literals, same as story 1.4. `ng-zorro-antd`'s `--ant-*` variables bridge from these same custom
properties in one place in `styles.css`, built on `ng-zorro-antd.dark.css`; a component never sets
an `--ant-*` variable directly, and never reaches a raw `ant-*` class from outside its wrapper.
Every corner stays at `--radius-default: 0`, including inside a `ng-zorro-antd` control. The
`nz-select`/`nz-date-picker` spike stays on the `/dev` harness, under `NzSelectModule` and
`NzDatePickerModule`, and never reaches `/records` or any real screen this story. `/records` uses
static in-memory data — no backend call this story.

**Ask First:** any token diverging from `DESIGN.md`. Any component beyond the seven named above.
Any `ng-zorro-antd` module import beyond `NzInputModule`, `NzSelectModule`, and `NzDatePickerModule`.

**Never:** real/backend data this story (story 1.6). A `<table>`. Pagination/infinite scroll. A
per-column filter or sort. A theming mechanism beside Tailwind's `@theme` pipeline and the
`--ant-*` bridge — no SCSS, no a second CSS framework. A raw `ant-*` class outside its wrapper
component. The `nz-select`/`nz-date-picker` spike wired into a real screen — it stays a `/dev`
harness probe.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Search-as-you-type | Query matches identity/meta/metric | List narrows live, no reload | N/A |
| Search, no match | Query matches nothing | List renders empty | N/A |
| Chip clear | Chip activated, then cleared | List returns to prior filtered state | N/A |
| Absent metric | Metric not set | Reads `not set`, row height unchanged | N/A |
| Settled record | Record marked settled | Every zone renders `ink-settled` | N/A |
| Breakpoint switch | Viewport crosses 768px | Layout swaps stacked ⇄ side-by-side | N/A |

</frozen-after-approval>

## Code Map

- `alpenflight/client/platform/src/app/app.routes.ts` -- swap `records`'s placeholder entry for
  the new demo host; add the `/dev/component-spike` route
- `alpenflight/client/platform/package.json` -- new dependency: `ng-zorro-antd` (MIT, `22.0.1`)
- `alpenflight/client/platform/src/styles.css` -- token source; reuse only, plus the new dark-mode
  `ng-zorro-antd.dark.css` import and the `--ant-*` bridge
- `.../dev/component-spike/component-spike.ts` (+html) -- new; `nz-select` and `nz-date-picker`
  rendered against the dark bridge, `/dev` route only, never wired to a real screen this story
- `.../shared/record-item/record-item.ts` (+html+css) -- new; the one row treatment
- `.../shared/list-group-header/list-group-header.ts` (+html) -- new; names a group + count
- `.../shared/record-list/record-list.ts` (+html+css) -- new; renders items, optional grouping
- `.../shared/search-field/search-field.ts` (+html) -- new
- `.../shared/filter-chip/filter-chip.ts` (+html) -- new
- `.../shared/sort-control/sort-control.ts` (+html) -- new
- `.../shared/list-toolbar/list-toolbar.ts` (+html+css) -- new; composes the three atoms
- `.../app/records/records.ts` (+html) -- new; demo host wiring toolbar + list to sample data
- `.../app/shell/shell.spec.ts` -- `RouterTestingHarness` pattern for `records.spec.ts`
- `alpenflight/client/features/system-status/system-status-card.ts` -- existing `OnPush`/standalone
  pattern

`.../` = `alpenflight/client/platform/src/app`

## Tasks & Acceptance

**Execution:**
- [ ] `package.json` -- add `ng-zorro-antd` `22.0.1`
- [ ] `styles.css` -- import `ng-zorro-antd.dark.css`, add the `--ant-*` token bridge, verify every
  corner is 0 and the palette matches `DESIGN.md` -- the one-time theme cost attempt 1 already paid,
  now against a dark base
- [ ] `record-item.ts` (+html+css) -- anatomy, phone/pointer layouts, settled/absent states -- the
  row every list reuses
- [ ] `record-list.ts` (+html+css) + `list-group-header.ts` (+html) -- render items on one
  background, optional grouping
- [ ] `search-field.ts` (+html) -- themed wrapper over `nz-input`; `filter-chip.ts`,
  `sort-control.ts` (+html each) -- hand-written, no library match
- [ ] `list-toolbar.ts` (+html+css) -- compose the atoms in fixed order
- [ ] `records.ts` (+html) + `app.routes.ts` -- mount toolbar + list at `/records` with sample
  data, replacing `DestinationPlaceholder` for that route
- [ ] `component-spike.ts` (+html) + `app.routes.ts` -- render `nz-select` and `nz-date-picker`
  against the dark bridge at `/dev/component-spike`; flag the override cost at checkpoint
- [ ] `record-item.spec.ts`, `record-list.spec.ts`, `list-toolbar.spec.ts`, `records.spec.ts` --
  cover the I/O matrix rows via existing `TestBed`/`RouterTestingHarness` conventions

**Acceptance Criteria:**
- Given a viewport under 768px, when `RecordList` renders, then items stack identity/meta left,
  metric/marker right, no fixed zone width.
- Given a viewport of 768px+, when `RecordList` renders, then items lay out side by side in fixed
  104px/flexible/88px zones.
- Given the search field, when the operator types, then only records matching identity/meta/metric
  (case-insensitive) remain, no submit control.
- Given a filter chip, when activated, then the list narrows and the chip shows its value + a clear
  control.
- Given the sort control, when changed, then it names the current sort/direction and reorders
  without hiding records.
- Given an absent metric, when the item renders, then it reads `not set` and keeps row height.
- Given a focused toolbar control, when it receives keyboard focus, then the existing focus ring
  appears.
- Given `nz-select` and `nz-date-picker` rendered on the `/dev/component-spike` route, when they
  open, then every corner reads 0 and the palette matches `DESIGN.md`'s dark tokens, with no
  light-theme leakage.

## Design Notes

**Static demo data, swapped later.** `records.ts` holds a small sample array; story 1.6 replaces it
with `httpResource`, unchanged public API.

**Sort interaction is a judgment call**, undocumented in `DESIGN.md`: click cycles fields, click on
the active field flips direction — flag at checkpoint, as 1.4 flagged its breakpoint.

**Chips combine with AND**, together with any active search query.

**The dark-mode override cost is unverified, unlike attempt 1's light-mode cost.** Attempt 1 themed
Ant Design's default *light* stylesheet, at about 150 lines of `!important` rules for corner radius
and control height. Nothing in that codebase prices a *dark-only* product. `ng-zorro-antd` ships an
official `ng-zorro-antd.dark.css`, so the fight starts from a themed dark base, not dark from
scratch — but the exact cost against `DESIGN.md`'s 7:1 contrast floor and named palette is unproven
until the `/dev/component-spike` spike runs. Flag the finding at checkpoint: if the cost is
attempt-1-sized, the architecture spine's provisional component-library decision (AD-23) is ready
to ratify; if it is materially larger, revisit before Epic 3 commits the typeahead and the date
field to the same library.

## Verification

**Commands:**
- `cd alpenflight && ./gradlew :client:platform:ngTest` -- expected: all specs pass
- `cd alpenflight && ./gradlew :client:platform:ngBuild` -- expected: build succeeds
- `cd alpenflight && ./gradlew build` -- expected: full CI-equivalent build stays green

**Manual checks (if no CLI):**
- `npm start --workspace=platform`, visit `/records`, resize across 768px, search, toggle a chip,
  change sort, confirm absent-value and settled-record styling.
- Visit `/dev/component-spike`, open `nz-select` and `nz-date-picker`, confirm zero-radius corners
  and no light-theme leakage against `DESIGN.md`'s palette.
