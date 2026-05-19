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

## Reactive Forms convention (S-007)

Reference: `src/app/features/clubs/edit/clubs-edit.page.ts` is the canonical typed-FormGroup form. New domain edit pages copy its shape; convention extracts below.

- **Validators live in the form definition** (`FormBuilder.group({...})`), never in the component template or submit handler. Custom validators are pure factory functions in a sibling `*.validators.ts` file when reused; inline when one-off. Pattern: `clubs-edit.validators.ts → slugAvailable(opts)`.
- **Typed `FormGroup<{...}>`** over `fb.nonNullable.control(...)` per field — no `null | undefined` in `getRawValue()`. The form-shape type names the controls explicitly (`type ClubForm = FormGroup<{ name: FormControl<string>; ... }>`).
- **Error rendering** is `<af-form-field [errors]="ctl.touched ? ctl.errors : null">` with `<af-field-errors>` wired by the form-field molecule. `field-errors.ts` maps validator key → translation key (`required` → `common.errors.required`; unknown → `common.errors.<key>`). New custom validators register a new error key — no template churn.
- **Inline (per-keystroke) validation by default** — sync validators use the default `updateOn: 'change'`. **`updateOn: 'blur'`** is reserved for _network_-backed async validators per [Angular's perf guidance](https://angular.dev/guide/forms/form-validation) (they'd otherwise fire on every keystroke). `slugAvailable` runs in-memory and keeps the default. The legacy top-level `MessageManager` error-bar pattern is **not** carried forward; errors render next to the offending control.
- **Touched gate avoids first-paint noise** — bind `[errors]` via `ctl.touched ? ctl.errors : null` so the field doesn't scream until the user has engaged it. `markAllAsTouched()` on submit-of-invalid so error tips render even if a field was never blurred.
- **Submit-disabled state:** `[disabled]="form.invalid || saveInFlight()"`. Don't roll your own dirty/pristine logic — `form.invalid` is the answer.
- **Edit vs. create — single component, two routes.** Route param presence is the mode discriminator (`isCreate = computed(() => routeId().get('id') === null)`). On `create` the form binds to a fresh `FormBuilder.group(...)`. On `edit` an `effect()` reads the entity from the feature store and `patchValue()`s. Immutable-post-create fields are `disable({ emitEvent: false })`d in the same effect; **re-enable on edit→new navigation** in the same effect — `patchValue` doesn't reset disabled state.
- **`getRawValue()` over `value`** — `value` skips disabled controls; create-mode submit needs all fields.
- **Server per-field errors** — on save failure, the store sets a `saveError` signal; the page's effect inspects it and maps known shapes onto `ctl.setErrors({ <key>: true })` matching the same error key the corresponding client-side validator would have surfaced. Generic / unknown save errors render at the top via the store's `saveError` line — visually distinguished from per-field validation.
- **Async validator pattern (in-memory):** `slugAvailable({ entities, currentId })` factory returns a `ValidatorFn`. Excludes `currentId` from the duplicate scan so edit-mode doesn't flag the row's own slug. Server 409 is still the authoritative duplicate gate; both paths set `{ duplicate: true }` so one error key surfaces consistently.

AC-DIR-3 (responsive form layout), AC-DIR-4 (IndexedDB draft auto-save), and AC-DIR-5 (Ctrl+S / Ctrl+D / Esc keyboard contract) are deferred until S-062c flight-edit lands — the first form complex enough to be load-bearing. Adding them speculatively now would build infra against the 4-field reference.

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
