---
id: S-062e
title: Fix af-date-picker mode=range zoneless deadlock
epic: E-04
status: todo
depends_on: [S-008]
acceptance:
  - `<af-date-picker mode="range" />` renders and accepts a date range without freezing the browser main thread under zoneless Angular.
  - `/dev/primitives` showcase (which already mounts the range variant) loads + screenshots in under 2 s — currently times out.
  - `/flights` filter bar reverts from the two-single-pickers workaround (`flights-list.page.ts`) back to a single range picker once the primitive is green; the page passes its existing Playwright spec with the range variant.
  - Playwright smoke for the primitive: navigate `/dev/primitives` → range picker visible → open panel → pick from + to → assert value emitted.
estimate: S
adr_refs: [0006, 0008, 0024]
origin: post-S-062b discovery
refined: false
---

## Context

During the manual smoke of S-062b's `/flights` page we discovered that `<af-date-picker mode="range" />` deadlocks the browser main thread under zoneless Angular. The freeze reproduces independently at `/dev/primitives` (which already mounts the range variant for showcase purposes) — page evaluate + screenshot both time out, indicating a tight synchronous loop or zone-dependent timer that never settles. The single-mode picker works fine.

S-062b worked around the bug by splitting the From / To date filter into two `<af-date-picker mode="single" />` instances. That UX is acceptable but loses the legacy "click + drag a range on one calendar" interaction. The proper fix lands here.

## Likely root cause (to verify during refine)

`<nz-range-picker>` (ng-zorro-antd) internally relies on zone-driven change detection for its floating panel + value-bridge between the two date inputs. Under zoneless Angular the panel emits state changes that never trigger a CD tick, causing a re-render loop. Candidate fixes: a manual `ChangeDetectorRef.markForCheck()` wrapper, replacing `nz-range-picker` with a custom CDK overlay + two single pickers internally, or upgrading ng-zorro to a version with zoneless support.

## Out of scope

- Replacing `af-date-picker` with a different vendor primitive — vendor swap is its own decision (ADR territory).
- The single-mode picker (works today).

## Pickup notes

- Reproducer: load `/dev/primitives` in `mock-auth` mode; the page never finishes hydrating.
- The S-062b workaround uses two `mode="single"` pickers in `alpenflight/web/src/app/features/flights/list/flights-list.page.ts` — revert that block to a single range picker once the primitive is green.
