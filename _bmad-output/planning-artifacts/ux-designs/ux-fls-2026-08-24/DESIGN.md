---
name: AlpenFlight
description: Flight operations for Swiss glider clubs. An instrument, not a web application. Dark only, sharp, exact, and legible in sunlight.
status: draft
created: 2026-08-24
updated: 2026-08-25
sources:
  - _bmad-output/planning-artifacts/briefs/brief-fls-2026-08-24/brief.md
  - _bmad-output/planning-artifacts/briefs/brief-fls-2026-08-24/addendum.md
  - docs/attempt-1/design-reference/ (history, not authority)
colors:
  surface-base: '#0A0E13'
  surface-panel: '#131A22'
  surface-raised: '#1C242E'
  surface-input: '#0F161D'
  surface-hover: '#1A232D'
  surface-selected: '#0E2029'
  ink-primary: '#F2F6FA'
  ink-secondary: '#A8B6C4'
  ink-settled: '#97A5B2'
  ink-disabled: '#5E6E7D'
  ink-on-live: '#04121A'
  line-hairline: '#1F2A35'
  line-strong: '#33414F'
  live: '#5FD0F0'
  live-dim: '#12303C'
  caution: '#FFB43D'
  caution-dim: '#3A2A0C'
  warning: '#FF6B6B'
  warning-dim: '#3A1618'
typography:
  micro:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 11px
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: 0.12em
  body:
    fontFamily: 'system-ui, "Segoe UI", "Helvetica Neue", Arial, sans-serif'
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.45
  value:
    fontFamily: 'ui-monospace, "SF Mono", "Cascadia Mono", "Roboto Mono", Menlo, monospace'
    fontSize: 15px
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: '-0.01em'
  value-lead:
    fontFamily: '{typography.value.fontFamily}'
    fontSize: 19px
    fontWeight: 600
    lineHeight: 1.15
  heading:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.3
  display:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 22px
    fontWeight: 600
    lineHeight: 1.2
rounded:
  DEFAULT: '0'
  sm: '0'
  md: '0'
  lg: '0'
  full: '9999px'
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 20px
  '6': 24px
  '8': 32px
  '10': 40px
  '12': 56px
  gutter: 16px
  topbar-h: 52px
  tabbar-h: 56px
  row-h: 44px
  row-h-dense: 32px
  item-h: 60px
  zone-identity: 104px
  zone-metric: 88px
  touch-min: 44px
  stamp-h: 56px
  container-max: 1440px
components:
  topbar:
    height: '{spacing.topbar-h}'
    background: '{colors.surface-panel}'
    borderBottom: '1px solid {colors.line-strong}'
    wordmarkColor: '{colors.live}'
    wordmarkType: '{typography.micro}'
  tabbar:
    height: '{spacing.tabbar-h}'
    background: '{colors.surface-panel}'
    borderTop: '1px solid {colors.line-strong}'
    labelType: '{typography.micro}'
    activeColor: '{colors.live}'
    activeIndicator: '2px solid {colors.live} (top edge)'
    inactiveColor: '{colors.ink-secondary}'
  field-row:
    minHeight: '{spacing.touch-min}'
    padding: '{spacing.2} {spacing.3}'
    background: '{colors.surface-base}'
    borderBottom: '1px solid {colors.line-hairline}'
    labelType: '{typography.micro}'
    labelColor: '{colors.ink-secondary}'
    valueType: '{typography.value}'
    valueColor: '{colors.ink-primary}'
    emptyColor: '{colors.ink-disabled}'
    focusBackground: '{colors.surface-selected}'
    focusMarker: 'inset 2px 0 0 {colors.live}'
    invalidMarker: 'inset 2px 0 0 {colors.warning}'
  stamp-button:
    height: '{spacing.stamp-h}'
    minWidth: 96px
    background: '{colors.live}'
    color: '{colors.ink-on-live}'
    type: '{typography.micro}'
    rounded: '{rounded.DEFAULT}'
    pressedBackground: '#8FE0F7'
    disabledBackground: '{colors.surface-raised}'
    disabledColor: '{colors.ink-disabled}'
  button-primary:
    height: 44px
    background: '{colors.live}'
    color: '{colors.ink-on-live}'
    type: '{typography.micro}'
    rounded: '{rounded.DEFAULT}'
  button-secondary:
    height: 44px
    background: 'transparent'
    color: '{colors.ink-primary}'
    border: '1px solid {colors.line-strong}'
    rounded: '{rounded.DEFAULT}'
  state-marker:
    padding: '2px {spacing.2}'
    border: '1px solid'
    type: '{typography.micro}'
    rounded: '{rounded.DEFAULT}'
    openColor: '{colors.live}'
    openBorder: '{colors.live-dim}'
    airborneColor: '{colors.live}'
    airborneBorder: '{colors.live}'
    lockedColor: '{colors.caution}'
    lockedBorder: '{colors.caution-dim}'
    billedColor: '{colors.ink-settled}'
    billedBorder: '{colors.line-strong}'
    unsentColor: '{colors.warning}'
    unsentBorder: '{colors.warning-dim}'
  record-item:
    minHeight: '{spacing.item-h}'
    minHeightDense: '{spacing.row-h}'
    padding: '{spacing.2} {spacing.3}'
    border: 'none'
    background: '{colors.surface-base}'
    hoverBackground: '{colors.surface-hover}'
    layoutPhone: 'stacked — identity over meta at full width, metric over marker at the right'
    layoutPointer: 'side by side — identity, meta, metric, marker in fixed zones'
    identityWidth: '{spacing.zone-identity}'
    identityType: '{typography.value-lead}'
    identityColor: '{colors.ink-primary}'
    metaType: '{typography.micro}'
    metaColor: '{colors.ink-secondary}'
    metricWidth: '{spacing.zone-metric}'
    metricType: '{typography.value}'
    metricColor: '{colors.ink-primary}'
    metricAlign: 'right'
    settledColor: '{colors.ink-settled}'
    liveMetricColor: '{colors.live}'
  list-group-header:
    background: '{colors.surface-panel}'
    borderBottom: '1px solid {colors.line-strong}'
    padding: '{spacing.2} {spacing.3}'
    type: '{typography.micro}'
    color: '{colors.ink-secondary}'
  list-toolbar:
    background: '{colors.surface-base}'
    padding: '{spacing.3}'
    gap: '{spacing.2}'
    order: 'search field, filter chips, sort control'
    borderBottom: '1px solid {colors.line-hairline}'
  sort-control:
    height: 32px
    background: '{colors.surface-panel}'
    border: '1px solid {colors.line-strong}'
    color: '{colors.ink-secondary}'
    type: '{typography.micro}'
    rounded: '{rounded.DEFAULT}'
    activeColor: '{colors.live}'
  filter-chip:
    height: 32px
    padding: '0 {spacing.3}'
    background: '{colors.surface-panel}'
    border: '1px solid {colors.line-strong}'
    color: '{colors.ink-secondary}'
    rounded: '{rounded.DEFAULT}'
    activeBackground: '{colors.live-dim}'
    activeBorder: '{colors.live}'
    activeColor: '{colors.live}'
  rule-card:
    background: '{colors.surface-panel}'
    border: '1px solid {colors.line-hairline}'
    orderType: '{typography.value-lead}'
    orderColor: '{colors.live}'
    clauseLabelType: '{typography.micro}'
    clauseLabelColor: '{colors.ink-secondary}'
    stopFlagColor: '{colors.caution}'
  trace-line:
    type: '{typography.value}'
    consumedColor: '{colors.live}'
    remainderColor: '{colors.ink-secondary}'
    totalColor: '{colors.ink-primary}'
    totalBorderTop: '1px solid {colors.line-strong}'
  dialog:
    background: '{colors.surface-raised}'
    border: '1px solid {colors.line-strong}'
    shadow: '{elevation.overlay}'
    rounded: '{rounded.DEFAULT}'
  focus-ring:
    outline: '2px solid {colors.live}'
    outlineOffset: '1px'
elevation:
  flat: 'none'
  overlay: '0 12px 32px -8px rgba(0, 0, 0, 0.66), 0 2px 6px rgba(0, 0, 0, 0.4)'
motion:
  instant: '0ms'
  fast: '110ms linear'
  reveal: '140ms linear'
---

# AlpenFlight — Design Spine

Peer document: [`EXPERIENCE.md`](./EXPERIENCE.md). This file owns how AlpenFlight looks.
`EXPERIENCE.md` owns how it works. Both win against any mock, wireframe, or import.

## Brand & Style

AlpenFlight is an instrument. It is not a web application with an aviation theme.

The duty flight leader uses it beside an aircraft, on a flying day, with interruptions. The treasurer uses it
to produce invoices that members dispute. Both jobs need the same thing: the screen states a fact,
and the fact is correct. Nothing on the screen exists to please. Every element either carries data,
or it accepts an input, or it is removed.

Three rules produce the whole appearance:

1. **Nothing is round.** Every corner is square. Radius exists only for the account portrait.
2. **Every number is monospaced and column aligned.** A wrong digit shows itself, because the
   digits above and below it line up.
3. **Colour is a signal, never a decoration.** The interface is grey. Cyan means live. Amber means
   locked. Red means wrong or unsent. A screen that shows no colour is a screen with nothing to
   report.

The result reads as a panel instrument. That is the intent. The product replaces a system that
clubs tuned over many years, so it must look like something that keeps working.

**Dark only.** There is no light mode and no theme control. The supplier accepted the sunlight and
battery cost of this choice. Section *Colors* states what the palette must do to earn it.

## Colors

**The contrast floor is unusual, and it is the price of the dark ground.** The duty flight leader reads this
screen outdoors, in direct sun, on a phone. Every colour that carries a value meets the WCAG AAA
ratio of 7:1 against `{colors.surface-base}`. The AA minimum of 4.5:1 is not sufficient here.
**Do not add a grey between `{colors.ink-settled}` and `{colors.ink-disabled}`.**

**Surfaces.** Four tones, and they differ by tone alone.

- **`{colors.surface-base}` `#0A0E13`** is the ground. Almost every screen sits on it, and every
  record item sits on it. **A list has one background.** There is no alternate row tone.
- **`{colors.surface-panel}` `#131A22`** raises the chrome: the top bar, the tab bar, list group
  headers, and rule cards.
- **`{colors.surface-raised}` `#1C242E`** is for dialogs and menus, which float.
- **`{colors.surface-input}` `#0F161D`** is darker than the ground. An input field recedes; it does
  not stand out. The value inside it is what the duty flight leader reads.

**Text.**

- **`{colors.ink-primary}` `#F2F6FA`** carries every value the duty flight leader must read. 17:1.
- **`{colors.ink-secondary}` `#A8B6C4`** carries field labels and column headings. 9.5:1.
- **`{colors.ink-settled}` `#97A5B2`** carries data that is complete and closed — a billed flight,
  a past season. 7.3:1. It is quiet. It is still readable outdoors.
- **`{colors.ink-disabled}` `#5E6E7D`** marks an empty field and a control that cannot be used. It
  is below the floor on purpose, because it never carries a value.

**Signals.** Three, and each one means exactly one thing.

- **`{colors.live}` `#5FD0F0` — cyan. Live.** An aircraft in the air. A field with focus. The
  primary action. The NOW control. Cyan is the colour of the thing that is happening now, and of
  the control that makes it happen.
- **`{colors.caution}` `#FFB43D` — amber. Locked.** A flight that the time gate closed. A rule that
  stops the engine. Amber does not mean error. It means the duty flight leader can no longer change this.
- **`{colors.warning}` `#FF6B6B` — red. Wrong or unsent.** A failed validation. A record that a
  device holds but has not sent. A conflict between two people. Red always names a thing the
  duty flight leader can act on.

Each signal has a dim companion (`{colors.live-dim}`, `{colors.caution-dim}`,
`{colors.warning-dim}`) for a background fill behind the signal colour. Never fill a large area with
a signal colour at full strength.

**Forbidden.** Gradients. Shadows used for hierarchy. A second blue. A green. Colour applied to a
row because of its category. Colour applied to make a screen look complete.

> **Caution: green is absent on purpose.** Green reads as "correct" and this product must never
> imply that a record is correct. It knows a record is complete, saved, or billed. It does not know
> that the duty flight leader typed the right time.

## Typography

Two families do all the work.

- **`{typography.body.fontFamily}`** — the system grotesque. It carries labels, headings, prose, and
  every name. It costs no network request, and no font file can fail to load at an airfield.
- **`{typography.value.fontFamily}`** — a monospace. It carries **every number and every
  identifier**: times, durations, dates, altitudes, counters, amounts, and aircraft registrations.
  `HB-3215` is an identifier, so it is monospaced.

The ramp is short, and the reason is density. Six roles, and no others.

| Role | Size | Use |
| --- | --- | --- |
| `{typography.micro}` | 11px, 600, uppercase, tracked | Field labels, column headings, tab labels, state markers, button labels |
| `{typography.body}` | 14px | Names, prose, remarks, help text |
| `{typography.value}` | 15px mono | Every number and identifier in a field or a cell |
| `{typography.value-lead}` | 19px mono | The sticky summary, the airborne row, the rule order number |
| `{typography.heading}` | 16px | Section and panel headings |
| `{typography.display}` | 22px | Screen titles, and nothing else |

**Rules.**

1. Set `font-variant-numeric: tabular-nums` on every numeric cell, including the ones set in
   `{typography.body}`.
2. Right-align a numeric column. Left-align a text column. Never centre a column.
3. Write a label in `{typography.micro}`. Never write a label in sentence case.
4. Never use italic. Never use a weight above 600.
5. A time is always four digits and a colon: `10:24`. A duration is always `HH:MM`: `01:18`. Never
   drop a leading zero.

## Layout & Spacing

The scale is 4px, and every gap is a step on it: 4, 8, 12, 16, 20, 24, 32, 40, 56.

**Alignment is a hard requirement, not a preference.** The supplier named a misaligned element as a
reason to distrust a screen. Every label in a column starts on the same x. Every value in a column
ends on the same x. A row that has no value keeps its height.

**Two densities, and the surface chooses.**

- `{spacing.row-h}` 44px is the default, and it is the minimum touch target. The phone always uses
  it.
- `{spacing.row-h-dense}` 32px is available on a pointer device, for the logbook and the reports.
  The duty flight leader turns it on. It is never the default.

**Breakpoints.** One column below 768px. Two columns from 768px. The dense desktop layout starts at
1200px, and the container stops at `{spacing.container-max}` 1440px.

**The top bar is `{spacing.topbar-h}` 52px and it is always visible.** On a phone the four
destinations sit in a bottom bar of `{spacing.tabbar-h}` 56px, so a thumb reaches them.

## Elevation & Depth

**Flat.** `{elevation.flat}` is the value for every surface on the page. Hierarchy comes from tone
and from a hairline rule, never from a shadow.

`{elevation.overlay}` exists for one purpose: a dialog or a menu that floats above the page. It is
the only shadow in the product.

**Motion is short and linear.** `{motion.fast}` 110ms for a state change. `{motion.reveal}` 140ms
for a conditional field that appears. `{motion.instant}` for anything the duty flight leader triggered with a
press — the press has already told them it worked.

**Forbidden.** Easing curves that overshoot. A bounce. A fade longer than 140ms. Any animation that
moves an element the duty flight leader is about to press. Any animation on a list that reorders.

## Shapes

`{rounded.DEFAULT}` is `0`. This applies to buttons, inputs, cards, dialogs, tables, markers, and
chips. There is no exception for a status marker: a state is a bordered rectangle, not a pill.

`{rounded.full}` exists for the account portrait in the top bar. That is its only use.

A state marker is a 1px border plus `{typography.micro}` text in the signal colour. It has no fill
at full strength. A filled marker uses the dim companion colour behind the signal text.

## Components

- **Top bar** — Wordmark in `{colors.live}` at `{typography.micro}`. The club home base and the
  account portrait sit at the right. On a pointer device the four destinations sit here. Border
  bottom `{colors.line-strong}`.
- **Tab bar (phone)** — Four labels in `{typography.micro}`. The active tab is `{colors.live}` with
  a 2px cyan rule on its top edge. Inactive is `{colors.ink-secondary}`. No icon-only tabs.
- **Field row** — Label left in `{typography.micro}`, value right in `{typography.value}`. Border
  bottom `{colors.line-hairline}`. Focus paints the row `{colors.surface-selected}` and sets a 2px
  cyan marker on the left edge. Invalid sets the same marker in `{colors.warning}`. An empty value
  reads `not set` in `{colors.ink-disabled}` — never a dash, never blank.
- **NOW control (`stamp-button`)** — `{spacing.stamp-h}` 56px tall, minimum 96px wide, full
  `{colors.live}` fill, label `NOW` in `{colors.ink-on-live}`. It is the largest control in the
  product, and it is the only control that is allowed to be the largest. It sits at the right of an
  airborne row, inside the thumb arc. It has no confirmation step.
- **State marker** — `OPEN`, `AIRBORNE`, `LOCKED`, `BILLED`, `UNSENT`. Bordered rectangle,
  `{typography.micro}`. `AIRBORNE` is the only marker with a full-strength border, because it is the
  only state that changes without a person acting.
- **Record item** — **The only list treatment in the product.** There is no data table. Every list
  of records uses this: the logbook, the airborne board, deliveries, members, aircraft, and
  reports, on the phone and on the pointer device alike. See *Record items* below for the anatomy.
- **List group header** — `{colors.surface-panel}`, `{typography.micro}` in
  `{colors.ink-secondary}`, with a `{colors.line-strong}` rule below. It names the group and
  carries its count: `IN THE AIR · 2`. The item alternation restarts under every group header.
- **List toolbar** — Search field, then filter chips, then the sort control, in that order. It sits
  above the first group header.
- **Sort control** — 32px, square, bordered. It names the current sort: `DATE ↓`. It is the only
  place sorting lives, because an item has no column header to carry it.
- **Filter chip** — 32px, square, bordered. Inactive is `{colors.ink-secondary}` on
  `{colors.surface-panel}`. Active is `{colors.live}` text on `{colors.live-dim}` with a cyan
  border, and it carries a clear control.
- **Rule card** — The run-order number sits at the left in `{typography.value-lead}`
  `{colors.live}`. `WHEN` and `THEN` are two labelled clauses in `{typography.micro}`. The
  engine-stop flag is a line in `{colors.caution}`. A drag handle sits at the right.
- **Trace line (dry run)** — One line per rule that fired. The consumed amount is `{colors.live}`.
  The remainder is `{colors.ink-secondary}`. The total sits below a `{colors.line-strong}` rule in
  `{colors.ink-primary}`.
- **Dialog** — `{colors.surface-raised}`, 1px `{colors.line-strong}` border,
  `{elevation.overlay}`. Square. One level deep, never two.
- **Focus ring** — 2px `{colors.live}`, 1px offset, on every focusable element. It is never removed
  and never replaced by a background change alone.

## Record items

**AlpenFlight has no data table.** A table gives every field the same weight, and the fields do not
have the same weight. A record item formats each field by how much the reader needs it.

**The anatomy.** Four parts, and each part has one job.

| Part | Type | Carries |
| --- | --- | --- |
| **Identity** | `{typography.value-lead}` mono, `{colors.ink-primary}` | The one value that names the record: the aircraft registration, the member name, the article number |
| **Meta** | `{typography.micro}`, `{colors.ink-secondary}` | The supporting facts, joined by ` · `: crew, times, location |
| **Metric** | `{typography.value}` mono, right-aligned | The one number the reader came for: block time, amount, expiry date |
| **Marker** | `{typography.micro}` in a bordered rectangle | The state: `OPEN`, `AIRBORNE`, `LOCKED`, `BILLED`, `UNSENT` |

An action may sit at the far right, outside the four parts. The NOW and LAND controls use that slot,
at `{spacing.stamp-h}` 56px.

**Two layouts, and the surface chooses. This is not one layout that reflows.**

**Phone — stacked.** A left block takes all the remaining width: identity on line 1, meta on line 2.
A right block sizes itself to its content: metric on line 1, marker on line 2. The meta therefore
gets almost the full width of the screen, and it does not wrap. **Do not apply the fixed zone widths
on a phone.** They waste the width the meta needs.

```
HB-3215                     01:18
S. AEBI · OFF 10:24     AIRBORNE
```

**Pointer device — side by side.** Four zones on one line, with fixed widths:
identity `{spacing.zone-identity}` 104px, meta flexible, metric `{spacing.zone-metric}` 88px
right-aligned, marker under the metric.

```
HB-3215   21.08 · L. FREI · OFF 11:02 · LSZF → LSZF     02:05
                                                       LOCKED
```

**Alignment is the reason for the fixed widths on a pointer device.** The supplier said that a
misaligned element makes a screen untrustworthy. Fixed zones give the alignment of a table without
giving every field the same weight. On a phone the same rule holds by a simpler means: every
identity starts at the same left edge, and every metric ends at the same right edge.

**A list has one background, and rows carry no rule.** There is no alternating tone and no border
between rows. The composition and the group headers do the separating. A group header restarts the
list.

**Colour in an item.**

- A live metric is `{colors.live}`. The elapsed time of an airborne flight is the only routine case.
- A settled record puts **every** zone in `{colors.ink-settled}`. A billed flight recedes as a whole,
  not one field at a time.
- Nothing else in an item is coloured.

**Dense mode** collapses the item from `{spacing.item-h}` 60px to `{spacing.row-h}` 44px, and puts
the identity, the meta, and the metric on one line with the marker at the far right. The zones keep
their widths. Dense mode is a pointer-device option, never a phone default.

**An item keeps its height when a value is absent.** An empty metric reads `not set` in
`{colors.ink-disabled}`. The items stay even, and no row below moves.

## Do's and Don'ts

| Do | Don't |
| --- | --- |
| Set every corner to `0` | Round a card, a button, or a status marker |
| Monospace and tabular for every number and registration | Set a time in the body face |
| Use cyan for live, amber for locked, red for wrong | Add a second accent, or any green |
| Show `not set` in `{colors.ink-disabled}` for an empty value | Leave a field blank or print a dash |
| Keep a row's height when it has no value | Let a row collapse and shift the rows below it |
| Right-align a metric, left-align an identity | Centre anything |
| Build every list from record items | Build a list as a table of equal cells |
| Format each field by how much the reader needs it | Give every field the same weight |
| Stack the item on a phone, so the meta gets the width | Apply the fixed zone widths on a phone |
| Hold the zone widths fixed on a pointer device | Let a zone size itself to its content there |
| Give a list one background | Alternate the row tone, or rule under every row |
| Put the sort control in the list toolbar | Put a sort or a filter in a column header |
| Use a hairline rule for separation in a form | Use a shadow for hierarchy |
| Hold motion at 110–140ms, linear | Ease, bounce, or fade for longer |
| Meet 7:1 for anything that carries a value | Introduce a mid grey that fails outdoors |
| Make NOW the largest control on the screen | Give any other control that weight |
| Fill a large area with a surface tone | Fill a large area with a signal colour |
