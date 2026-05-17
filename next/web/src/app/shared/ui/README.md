# shared/ui — component primitives kit

`af-*` wrappers over **ng-zorro-antd 21.x**, with theming driven by **Tailwind v4** `@theme` tokens. Atomic-design layering enforced by ESLint.

## Layout

```
shared/ui/
├── atoms/        single-purpose primitives
│   ├── af-button/
│   ├── af-input/    native <input>, no ng-zorro picker (AC-DIR-9)
│   └── af-select/   nz-select with nzShowSearch on
├── molecules/    compositions of 2-N atoms
│   ├── af-form-field/    nz-form-item + label + error tip
│   └── af-field-errors/  FormControl.errors → translation keys (pure)
├── organisms/    feature-agnostic compositions
│   ├── af-data-table/    list-based (<ul>), no <table>
│   ├── af-date-picker/   range + single mode
│   ├── af-autocomplete/  recency-bias dropdown
│   └── af-nav-bar/       layout-sider at md+, drawer below
├── density/      <af-density-provider> + DensityService
├── viewport/     ViewportService (signal-based MQL tracking)
├── recency/      RecentlyUsedService (localStorage-backed)
└── locale/       LocaleService + TRANSLATION_ADAPTER seam
```

## Theming

Tailwind v4 `@theme { ... }` in `src/styles.css` is the **single source of brand tokens**. ng-zorro's `--ant-*` CSS variables are derived from Tailwind tokens in the same file — Tailwind tokens authoritative, ng-zorro vars consume.

To change a brand color: edit the `--color-brand-*` token in `@theme`. The `--ant-primary-color` bridge picks it up automatically. Never override `--ant-*` directly.

## Density

`<af-density-provider>` directive sets `data-density="comfortable|dense"` on its host AND publishes the value via `DensityService.density` signal. Every `af-*` wrapper reads the signal and flips `[nzSize]` accordingly.

- `comfortable` (default at <lg): 44 × 44 px touch targets, 1 rem body type.
- `dense` (default at ≥lg): 28 × 28 px for icon-only buttons, 0.875 rem body type.

The directive's override is **global** (one DensityService instance, root-provided). Per-subtree DI-scoped override is deferred until a real consumer needs it.

## i18n

`LocaleService.set('de' | 'fr' | 'it' | 'en')` is the single switch. It calls:
- `NzI18nService.setLocale(de_DE | fr_FR | it_IT | en_US)` — ng-zorro UI strings.
- `TRANSLATION_ADAPTER.setActiveLang(locale)` — the seam S-005 fills with transloco / `@angular/localize`.
- `document.documentElement.lang` — screen-reader / browser cue.

Default `TRANSLATION_ADAPTER` is no-op; S-005 replaces the provider.

## Native input types (AC-DIR-9)

Prefer native `<input type="time" | type="date">` inside `<af-form-field>` over ng-zorro's custom pickers. Only `<af-date-picker mode="range">` uses `nz-range-picker` (the load-bearing flight-form case).

## Label association (af-form-field)

The consumer is responsible for matching `<af-form-field [for]="X">` with the projected input's `id="X"`:

```html
<af-form-field label="Email" for="emailField" [required]="true" [errors]="ctl.errors">
  <input id="emailField" [formControl]="ctl" type="email" />
</af-form-field>
```

Auto-wiring is not done today; the first feature consumer drives the decision.

## Lists, not tables

Per operator directive 2026-05-17, `af-data-table` renders `<ul role="list">` with `<li>` items — never a `<table>` or `nz-table`. Layout is CSS-responsive via `@container (min-width: 768px)`: items stack vertically at narrow viewports, flow horizontally above. The consumer projects `[primary]`, `[secondary]`, `[meta]` templates.

## Bundle posture

- Per-component ng-zorro imports only (`NzButtonModule` not `NgZorroAntdModule`). ESLint enforces via `regex: '^ng-zorro-antd$'` ban.
- Atomic layering enforced: atoms ↛ molecules/organisms; molecules ↛ organisms.
- `ng-zorro-antd.variable.min.css` is imported once at the app root in `src/styles.css`. Adds ~600 KB raw / ~60 KB transfer. Operator-pinned `initial` budget = 5 MB raw.

## Local storage

Banned globally except `shared/ui/recency/` (per the eslint override block). `RecentlyUsedService` is the only sanctioned consumer in this folder. S-021 will add the auth-token storage seam.

## Playwright snapshot suite (deferred)

The refined test plan called for 17 Playwright specs at 4 viewports covering touch-target physical-size, density propagation, axe-core, focus-ring contrast, locale lockstep, and card/row switch. The suite is **deferred to land with the first real feature consumer** (S-049 Locations CRUD, or S-048 Clubs CRUD with mocked auth per operator suggestion 2026-05-17) — testing the kit primitives against a real domain surface gives stronger validation than the synthetic `/dev/primitives` showcase route.

The `/dev/primitives` route remains in place as a kit catalog for visual probing during development.
