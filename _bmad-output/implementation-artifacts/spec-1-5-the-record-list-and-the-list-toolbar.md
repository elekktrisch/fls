---
title: 'Story 1.5: The record list and the list toolbar'
type: 'feature'
created: '2026-08-31'
status: 'done'
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
stylesheet. Mount `/records` with a static demo dataset. Story 1.6 swaps in real data.

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
Every corner stays at `--radius-default: 0`, including inside a `ng-zorro-antd` control. `/records`
uses static in-memory data — no backend call this story.

**Ask First:** any token diverging from `DESIGN.md`. Any component beyond the seven named above.
Any `ng-zorro-antd` module import beyond `NzInputModule`.

**Never:** real/backend data this story (story 1.6). A `<table>`. Pagination/infinite scroll. A
per-column filter or sort. A theming mechanism beside Tailwind's `@theme` pipeline and the
`--ant-*` bridge — no SCSS, no a second CSS framework. A raw `ant-*` class outside its wrapper
component. `nz-select` or `nz-date-picker` this story — deferred to a follow-up spike (see
Design Notes).

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
  the new demo host
- `alpenflight/client/platform/package.json` -- new dependency: `ng-zorro-antd` (MIT, `22.0.1`)
- `alpenflight/client/platform/src/styles.css` -- token source; reuse only, plus the new dark-mode
  `ng-zorro-antd.dark.css` import and the `--ant-*` bridge
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
- [x] `package.json` -- add `ng-zorro-antd` `22.0.1`
- [x] `styles.css` -- import `ng-zorro-antd.dark.css`, add the `--ant-*` token bridge, verify every
  corner is 0 and the palette matches `DESIGN.md` -- the one-time theme cost attempt 1 already paid,
  now against a dark base
- [x] `record-item.ts` (+html+css) -- anatomy, phone/pointer layouts, settled/absent states -- the
  row every list reuses
- [x] `record-list.ts` (+html+css) + `list-group-header.ts` (+html) -- render items on one
  background, optional grouping
- [x] `search-field.ts` (+html) -- themed wrapper over `nz-input`; `filter-chip.ts`,
  `sort-control.ts` (+html each) -- hand-written, no library match
- [x] `list-toolbar.ts` (+html+css) -- compose the atoms in fixed order
- [x] `records.ts` (+html) + `app.routes.ts` -- mount toolbar + list at `/records` with sample
  data, replacing `DestinationPlaceholder` for that route
- [x] `record-item.spec.ts`, `record-list.spec.ts`, `list-toolbar.spec.ts`, `records.spec.ts` --
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

## Design Notes

**Static demo data, swapped later.** `records.ts` holds a small sample array; story 1.6 replaces it
with `httpResource`, unchanged public API.

**Sort interaction is a judgment call**, undocumented in `DESIGN.md`: click cycles fields, click on
the active field flips direction — flag at checkpoint, as 1.4 flagged its breakpoint.

**Chips combine with AND**, together with any active search query.

**The `nz-select`/`nz-date-picker` dark-mode spike is deferred**, not part of this story
(`deferred-work.md`). This story still pays the one-time `--ant-*` bridge cost for `nz-input`
alone; whether `ng-zorro-antd`'s dark-mode override cost holds up for a select/date control against
`DESIGN.md`'s 7:1 contrast floor stays an open question for that follow-up spike, ahead of Epic 3
committing the typeahead and the date field to the same library. AD-23 stays provisional until then.

## Verification

**Commands:**
- `cd alpenflight && ./gradlew :client:platform:ngTest` -- expected: all specs pass
- `cd alpenflight && ./gradlew :client:platform:ngBuild` -- expected: build succeeds
- `cd alpenflight && ./gradlew build` -- expected: full CI-equivalent build stays green

**Manual checks (if no CLI):**
- `npm start --workspace=platform`, visit `/records`, resize across 768px, search, toggle a chip,
  change sort, confirm absent-value and settled-record styling.

## Suggested Review Order

**Core anatomy**

- The four-zone contract (identity/meta/metric/marker) and its settled/absent states, as signals.
  [`record-item.ts:35`](../../alpenflight/client/platform/src/app/shared/record-item/record-item.ts#L35)

- Breakpoint switch from stacked (phone) to fixed 104px/88px zones (pointer), at 768px.
  [`record-item.css:99`](../../alpenflight/client/platform/src/app/shared/record-item/record-item.css#L99)

**Sort and grouping interaction**

- Demo host groups by date only while sort is also `date`, so a duration sort never traps items
  under a stale date header.
  [`records.ts:241`](../../alpenflight/client/platform/src/app/records/records.ts#L241)

- `RecordList` stays sort-agnostic: it only buckets by first appearance and leaves ordering to the
  caller.
  [`record-list.ts:27`](../../alpenflight/client/platform/src/app/shared/record-list/record-list.ts#L27)

- Duration comparator places an absent value last, unconditionally on direction.
  [`records.ts:163`](../../alpenflight/client/platform/src/app/records/records.ts#L163)

- Direction lives inside each comparator, not as an outer multiplier, so it can't invert
  null-placement.
  [`records.ts:216`](../../alpenflight/client/platform/src/app/records/records.ts#L216)

**ng-zorro-antd dark-theme bridge**

- The one-time `--ant-*` token bridge — the only place a component may reach a raw `ant-*` class.
  [`styles.css:151`](../../alpenflight/client/platform/src/styles.css#L151)

- Restates the focus ring `ng-zorro-antd.dark.css`'s own `:focus` rule would otherwise suppress.
  [`search-field.ts:41`](../../alpenflight/client/platform/src/app/shared/search-field/search-field.ts#L41)

**Toolbar composition and accessibility**

- Composes search, chips, sort in the fixed order the spec requires.
  [`list-toolbar.ts:16`](../../alpenflight/client/platform/src/app/shared/list-toolbar/list-toolbar.ts#L16)

- `aria-current` marks the one active field in this mutually-exclusive sort group.
  [`sort-control.html:9`](../../alpenflight/client/platform/src/app/shared/sort-control/sort-control.html#L9)

**Routing**

- Mounts the demo host at `/records`, replacing story 1.4's placeholder for that destination.
  [`app.routes.ts:27`](../../alpenflight/client/platform/src/app/app.routes.ts#L27)

**Peripherals**

- I/O matrix coverage, including the duration/grouping regression the review surfaced.
  [`records.spec.ts:106`](../../alpenflight/client/platform/src/app/records/records.spec.ts#L106)

- Component-level coverage for anatomy, grouping, and toolbar composition.
  [`record-item.spec.ts:1`](../../alpenflight/client/platform/src/app/shared/record-item/record-item.spec.ts#L1)
