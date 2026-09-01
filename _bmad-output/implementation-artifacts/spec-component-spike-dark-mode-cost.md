---
title: 'Component spike: ng-zorro-antd dark-theme override cost'
type: 'feature'
created: '2026-09-01'
status: 'done'
route: 'one-shot'
context: []
---

# Component spike: ng-zorro-antd dark-theme override cost

## Intent

**Problem:** `DESIGN.md` fixes a dark-only, zero-radius, exact-palette design system. Story 1.5
deferred a spike (`deferred-work.md`, source_spec spec-1-5) to price the cost of overriding
`ng-zorro-antd`'s dark theme to match it, before Epic 3 commits the typeahead and the date field
to this library.

**Approach:** Add `ng-zorro-antd` `22.0.1` to `client/platform`. Add a `--ant-*` bridge to
`styles.css`, deriving from this app's own tokens, and a `/dev/component-spike` route (never wired
to a real screen) rendering an `nz-select` and an `nz-date-picker` against `ng-zorro-antd.dark.css`.

**Verdict — the cost is real and structural, not attempt-1-sized:**

1. **No native CSS-variable theming.** `ng-zorro-antd` 22.0.1's dark stylesheet is fully
   Less-compiled; it exposes zero `--ant-*` variables for background/border/radius/text. The
   bridge in `styles.css` is this app's own indirection layer, consumed by hand-written overrides
   — not a native hookup.
2. **CDK-overlay content cannot be scoped by `:host ::ng-deep`.** The dropdown/calendar panel
   portals to a container near `<body>`, outside the component's DOM subtree. Verified
   empirically mid-build: with `:host ::ng-deep`, the panel's computed background was
   `rgb(31, 31, 31)` — `ng-zorro-antd`'s own default, not this bridge's `#1c242e` — proving the
   override never matched. Fixed with bare `::ng-deep` (no `:host`), the documented pattern for
   theming overlay content, plus one specificity fix where the library's own compound selector
   (`.ant-picker-cell .ant-picker-cell-inner`) otherwise beat a single-class override.
3. **One missing DI provider crashed the route at runtime** (`NzDateAdapter` — `nz-date-picker`
   needs `provideNzI18n` + `provideNzDateFnsAdapter`), invisible to `ngBuild`/`ngTest` and caught
   only by opening the route in a real browser.
4. **No dark-only bundle.** Dark theming ships as one 761 KB (654 KB minified) file covering all
   ~70 components — no per-component split. Adopting it at all means paying for the whole library's
   CSS. This pushed the production initial bundle to 1.40 MB, forcing the budget up from
   `500kB/1MB` to `900kB/1.5MB` (still a warning, not an error, at the current size).
5. What holds up: once the two DOM situations (inline field vs. portaled panel) and their
   respective CSS techniques are known, the override surface is a finite, learnable set. Corners,
   palette, and locale all verified correct by direct browser inspection after the fixes above.

**Recommendation for AD-23:** the palette/radius outcome is achievable and now proven — but the
bundle-size cost (no per-component dark split) and the CDK-overlay theming technique are real,
non-trivial costs Epic 3 should plan for, not treat as solved. Consider lazy-loading `ng-zorro-antd`
modules per feature and a non-global, dynamically-injected dark stylesheet before committing more
components to this library, rather than accepting the app-wide bundle/style cost by default.

## Suggested Review Order

- The `--ant-*` bridge — this app's own tokens, not a native `ng-zorro-antd` hookup (see the block
  comment above it explaining why).
  [`styles.css:150`](../../alpenflight/client/platform/src/styles.css#L150)

- The two DOM situations and why each needs a different `::ng-deep` technique — the load-bearing
  comment for the whole override file.
  [`component-spike.css:42`](../../alpenflight/client/platform/src/app/dev/component-spike.css#L42)

- Inline field overrides (`:host ::ng-deep`) — correctly scoped, verified 0px radius.
  [`component-spike.css:61`](../../alpenflight/client/platform/src/app/dev/component-spike.css#L61)

- Portaled dropdown/panel/calendar overrides (bare `::ng-deep`) — the fix for the CDK-overlay
  scoping gap found during verification.
  [`component-spike.css:93`](../../alpenflight/client/platform/src/app/dev/component-spike.css#L93)

- `NzI18nService`/`NzDateAdapter` providers — required at app root because both services are
  `providedIn: 'root'`; a route-scoped provider can't reach them.
  [`app.config.ts:9`](../../alpenflight/client/platform/src/app/app.config.ts#L9)

- The isolated route — deliberately outside `DESTINATIONS`, never in the nav.
  [`app.routes.ts:20`](../../alpenflight/client/platform/src/app/app.routes.ts#L20)

- The bundle-budget increase — a direct, quantified consequence of adopting the library's
  monolithic dark stylesheet.
  [`angular.json`](../../alpenflight/client/platform/angular.json)
