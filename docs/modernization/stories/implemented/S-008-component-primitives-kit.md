---
id: S-008
title: Component primitives kit + Tailwind design tokens
epic: E-01
status: done
started_at: 2026-05-17
done_at: 2026-05-17
github_issue: 54
github_pr: 55
depends_on: [S-002]
acceptance:
  - Component primitives are sourced from **ng-zorro-antd** (chosen 2026-05-17) paired with **Tailwind v4 design tokens**; thin `af-*` wrappers live under `next/web/src/app/shared/ui/`.
  - A baseline walking-skeleton UI kit exists under `next/web/src/app/shared/ui/` organised by atomic-design taxonomy (atoms / molecules / organisms — see `next/web/CLAUDE.md` §1).
    - **Atoms:** `<af-button>`, `<af-input>`, `<af-select>`. (`<af-icon>`, `<af-badge>` JIT-deferred.)
    - **Molecules:** `<af-form-field>` (label + input + error wiring), `<af-field-errors>` (consumed by S-007). (`<af-search-input>`, `<af-time-now-button>` JIT-deferred.)
    - **Organisms:** `<af-data-table>` (row+card mode), `<af-date-picker>` (range+single), `<af-autocomplete>` (recency-bias dropdown), `<af-nav-bar>`. (`<af-dialog>`, `<af-sticky-bar>`, `<af-accordion-section>` JIT-deferred.)
    - **Services + directives:** `ViewportService`, `DensityService`, `RecentlyUsedService`, `LocaleService`, `<af-density-provider>` directive.
  - Tailwind v4 design tokens (colors, spacing scale, type scale, breakpoints, density-scoped tokens) live in `src/styles.css` inside the `@theme { ... }` block (CSS custom properties — `--color-brand-500: oklch(...)`, Roboto as `--font-sans` per operator); `--ant-*` ng-zorro variables derive from Tailwind tokens in the same stylesheet. No ad-hoc colours / sizes outside the token set. **No `tailwind.config.js`** — v4 is CSS-first.
  - Layering is enforced by ESLint `no-restricted-imports`: atoms cannot import molecules/organisms; molecules cannot import organisms; ng-zorro umbrella import (`from 'ng-zorro-antd'`) is banned in favour of per-component entry points.
  - A11y baseline per `next/web/CLAUDE.md` §5 holds for every primitive: visible focus ring at both densities, keyboard reachable, accessible name; ng-zorro `nz-modal` + `nz-date-picker` use CDK `Overlay` + `FocusTrap` under the hood.
estimate: M
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, performance-engineer]
context7_last_checked: 2026-05-17
---

## Context
ADR 0004 deferred the component-primitives choice to phase 4. The operator (2026-05-17) chose **ng-zorro-antd as the primary component-library source**, paired with **Tailwind v4 tokens** for project-specific theming. From-to range picker + searchable autocomplete with dropdown — the two organisms that decided the fork — are first-class in ng-zorro. Atomic-design taxonomy + folder layout were pre-staged in S-002 (see `next/web/CLAUDE.md` §1) — this story fills the empty `atoms/`, `molecules/`, `organisms/` folders with thin `af-*` wrappers over ng-zorro components plus the directives required by the mobile-first / dense-desktop directives (AC-DIR-1..AC-DIR-11 below).

## Acceptance criteria
See frontmatter. **AC-text drift flagged** (see `## Open design questions` Q2): the AC list still uses `<fls-*>` selectors and names Tailwind+CDK as the recommendation. The post-rebrand convention is `<af-*>` (per `next/web/CLAUDE.md` §6) and ng-zorro is the chosen primary source — operator decides whether to amend ACs.

## Tasks

Superseded by the design notes below.

<!-- modernize-refine: start -->

## Design notes

### Module layout

- **Server:** none. Frontend-only story; no `next/server/` touch.
- **DB:** none. ADR 0022 directive 2 — zero migration.
- **Client (kit folders, every primitive in its own folder with a barrel `index.ts`):**

  ```
  next/web/src/app/shared/ui/
    atoms/
      af-button/            (wraps nz-button)            [walking-skeleton]
      af-input/             (native <input>, no nz-input) [walking-skeleton]
      af-select/            (wraps nz-select)            [walking-skeleton]
      af-icon/              (wraps nz-icon)              [JIT — defer]
      af-badge/             (wraps nz-badge)             [JIT — defer]
    molecules/
      af-form-field/        (nz-form-item|label|control) [walking-skeleton]
      af-field-errors/      (FormControl.errors + transloco) [walking-skeleton]
      af-search-input/      (nz-input-group + nz-icon)   [JIT — defer]
      af-time-now-button/   (native button + type=time)  [JIT — S-062c]
    organisms/
      af-data-table/        (nz-table + card-mode)       [walking-skeleton]
      af-date-picker/       (nz-range-picker + nz-date-picker) [walking-skeleton]
      af-autocomplete/      (nz-select[showSearch] + dropdown render) [walking-skeleton]
      af-nav-bar/           (nz-menu + nz-layout-sider)  [walking-skeleton]
      af-dialog/            (NzModalService wrapper)     [JIT — defer]
      af-sticky-bar/        (pure Tailwind layout)       [JIT — S-062c]
      af-accordion-section/ (nz-collapse + nz-collapse-panel) [JIT — S-062c]
    density/
      af-density-provider.directive.ts                   [walking-skeleton]
      density.service.ts                                 [walking-skeleton]
    viewport/
      viewport.service.ts   (isBelow('md') signal)       [walking-skeleton]
    recency/
      recently-used.service.ts  (localStorage-backed)    [walking-skeleton]
    locale/
      locale.service.ts    (NzI18nService + transloco sync) [walking-skeleton]
    README.md              (kit conventions, ng-zorro mapping, density rules)
  next/web/src/app/dev/primitives/
    primitives-showcase.page.ts   (route: /dev/primitives)
    <one-per-primitive>.demo.ts   (mounted at sm/md/lg/xl in Playwright)
  ```

  **Walking-skeleton set (10 primitives + 4 services/directives):** `af-button`, `af-input`, `af-select`, `af-form-field`, `af-field-errors`, `af-data-table`, `af-date-picker`, `af-autocomplete`, `af-nav-bar`, `af-density-provider` + `DensityService`, `ViewportService`, `RecentlyUsedService`, `LocaleService`. Everything else is JIT — land when its consumer story is implemented (S-062c is the heaviest JIT pull).

### ng-zorro integration shape

**Standalone per-component imports only.** Every wrapper imports its single ng-zorro entry point — `NzButtonModule`, `NzSelectModule`, `NzTableModule`, etc. No `NgZorroAntdModule` allowed; banned by ESLint (see below).

**Theming via CSS variables (no Less).** The project is on Angular CLI esbuild. ng-zorro's CSS-variable build (`ng-zorro-antd/ng-zorro-antd.variable.min.css`) is imported in `src/styles.css`; `--ant-*` variables are remapped from Tailwind v4 `@theme` tokens in the same stylesheet. No `@angular-builders/custom-webpack`. No `less-loader`. No compact-theme import — compact density is handled via the density-provider directive (see below).

```css
/* src/styles.css — additions on top of the existing @theme block */
@import 'tailwindcss';
@import 'ng-zorro-antd/ng-zorro-antd.variable.min.css';

@theme {
  /* existing brand tokens */
  --color-brand-500: oklch(0.62 0.18 254.6);
  --color-brand-600: oklch(0.55 0.2 254.6);
  --font-display: 'Inter', system-ui, sans-serif;

  /* AC-DIR-1 — breakpoint tokens */
  --breakpoint-sm: 360px;
  --breakpoint-md: 768px;
  --breakpoint-lg: 1024px;
  --breakpoint-xl: 1440px;

  /* density-neutral spacing + radius scale (consumed by both ng-zorro and tailwind) */
  --radius-md: 0.375rem;
  --font-sans: 'Inter', system-ui, sans-serif;
}

/* AC-DIR-2 — density-scoped tokens (Tailwind-side; ng-zorro side handled via nzSize) */
:root, [data-density="comfortable"] {
  --space-row: 0.75rem;
  --space-field: 1rem;
  --font-size-body: 1rem;
  --row-height: 2.75rem;     /* ≥ 44px for touch */
}
[data-density="dense"] {
  --space-row: 0.375rem;
  --space-field: 0.5rem;
  --font-size-body: 0.875rem;
  --row-height: 1.75rem;     /* 28px for dense-desktop */
}

/* ng-zorro -> Tailwind token bridge */
:root {
  --ant-primary-color: var(--color-brand-500);
  --ant-primary-color-hover: var(--color-brand-600);
  --ant-primary-color-active: var(--color-brand-700);
  --ant-border-radius-base: var(--radius-md);
  --ant-font-family: var(--font-sans);
  --ant-success-color: oklch(0.65 0.18 145);
  --ant-error-color: oklch(0.60 0.22 25);
  --ant-warning-color: oklch(0.78 0.16 80);
}

/* AC-DIR-3 — touch-target enforcement on comfortable density.
   Chosen: top-level rule (not per-host ::ng-deep) — fewer scoped overrides, one source of truth. */
[data-density="comfortable"] .ant-btn,
[data-density="comfortable"] .ant-input,
[data-density="comfortable"] .ant-select-selector {
  min-height: 44px;
}
[data-density="comfortable"] .ant-btn-icon-only {
  min-width: 44px;
}
[data-density="dense"] .ant-btn-icon-only {
  min-width: 28px;
  min-height: 28px;
}
```

**Density mapping (AC-DIR-2).** `<af-density-provider>` is an attribute directive that:
1. Reads viewport via `ViewportService` (signal-derived from `window.matchMedia('(min-width: 1024px)')`) and emits `data-density="dense"` at `≥lg`, `comfortable` otherwise.
2. Publishes a `density: Signal<'comfortable' | 'dense'>` on injectable `DensityService`.
3. Allows override via `[afDensityProvider]="'dense'"` for subtrees.

Each `<af-*>` wrapper injects `DensityService` and binds `[nzSize]="density() === 'dense' ? 'small' : 'default'"` to its ng-zorro child. CSS-variable density on the Tailwind side (utility-class layout primitives like `<af-sticky-bar>`) is driven entirely by the `data-density` attribute selector — zero JS.

**i18n lockstep.** `LocaleService` is the single switch — it owns both ng-zorro's `NzI18nService.setLocale(...)` and the S-005 i18n library's locale set call. Defined in `shared/ui/locale/`, consumed by the `LocaleSwitcher` molecule (lands with S-005). API:

```ts
// shared/ui/locale/locale.service.ts
export type AppLocale = 'de' | 'fr' | 'it' | 'en';

@Injectable({ providedIn: 'root' })
export class LocaleService {
  private readonly nzI18n = inject(NzI18nService);
  private readonly translate = inject(TranslocoService); // or @angular/localize equivalent — S-005 picks
  readonly current = signal<AppLocale>('de');

  set(locale: AppLocale): void {
    const nz = { de: de_DE, fr: fr_FR, it: it_IT, en: en_US }[locale];
    this.nzI18n.setLocale(nz);
    this.translate.setActiveLang(locale);
    document.documentElement.lang = locale;
    this.current.set(locale);
  }
}
```

S-005 wires the bootstrap call (initial locale from user prefs / browser); S-008 just exposes the wrapper.

**Zoneless + OnPush.** `app.config.ts` already pins `provideZonelessChangeDetection()`. All `af-*` components run `changeDetection: ChangeDetectionStrategy.OnPush`. ng-zorro v19+ ships OnPush-compatible components across the board; no current known-bad list. **Assumption (surface at implement time — see `## Open design questions` Q1):** ng-zorro v19.3.1 (latest Context7-indexed) must be verified against Angular 21 peer deps.

### `<af-data-table>` (AC-DIR-4)

Row mode (`≥md`) renders `nz-table`. Card mode (`<md`) renders a flexbox stack of `nz-card`. Mode resolved by `ViewportService.isBelow('md')()` signal; overridable via `[mode]="'row' | 'card' | 'auto'"`. Consumer provides three `<ng-template>` slots for card mode: `[primary]`, `[secondary]`, `[meta]`.

```ts
// af-data-table.component.ts — public API signature
@Component({
  selector: 'af-data-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzTableModule, NzCardModule, NgTemplateOutlet],
  // template branches on viewport().isBelow('md') ? card : row
})
export class AfDataTableComponent<T> {
  readonly items = input.required<readonly T[]>();
  readonly columns = input.required<readonly DataTableColumn<T>[]>(); // {key, label, sortable, cellTemplate?}
  readonly mode = input<'row' | 'card' | 'auto'>('auto');
  readonly trackBy = input<TrackByFunction<T>>((_, i) => i);
  readonly pageSize = input<number>(20);
  readonly loading = input<boolean>(false);
  readonly virtualScroll = input<boolean>(false);          // seam — no-op until S-047 turns it on

  // Card-mode template projection
  readonly primary = contentChild<TemplateRef<{ $implicit: T }>>('primary');
  readonly secondary = contentChild<TemplateRef<{ $implicit: T }>>('secondary');
  readonly meta = contentChild<TemplateRef<{ $implicit: T }>>('meta');

  readonly sortChange = output<{ key: keyof T; direction: 'asc' | 'desc' | null }>();
  readonly pageChange = output<{ page: number; pageSize: number }>();
}
```

Sort + pagination state is *not* stored inside the table — events out, NgRx Signal Store entities in (ADR 0006). S-047 is the first real consumer.

### `<af-autocomplete>` (AC-DIR-5)

**Pick: `nz-select` with `[nzShowSearch]` + `[nzDropdownRender]` custom template** (not `nz-auto-complete`). Reason: `nz-select` gives chip-style selected display + multi-select fallback for free, and `nzDropdownRender` is the documented hook for the "Recently used" group at the top of the dropdown. `nz-auto-complete` is closer to a raw input with a dropdown — no chip mode, weaker keyboard model.

```ts
// af-autocomplete.component.ts — public API signature
@Component({ selector: 'af-autocomplete', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush })
export class AfAutocompleteComponent<T extends { id: string | number }> {
  readonly primitiveKey = input.required<string>();          // e.g. 'aircraft', 'pilot', 'location' — recency scope key
  readonly items = input.required<readonly T[]>();
  readonly searchFields = input.required<readonly (keyof T)[]>();  // fuzzy-match fields
  readonly labelFn = input<(item: T) => string>((it) => String((it as { name?: string }).name ?? it.id));
  readonly recent = input<boolean>(true);                     // top "Recently used (7d)" group
  readonly recentWindowDays = input<number>(7);
  readonly debounceMs = input<number>(150);
  readonly placeholder = input<string>('');
  readonly value = model<T | null>(null);                     // two-way bound for FormControl interop
}
```

`RecentlyUsedService` is `@Injectable({ providedIn: 'root' })`, persists `{ primitiveKey -> { itemId -> timestamp } }` in `localStorage` under key `af.recently-used.v1`. Localstorage access requires the ESLint per-file allowlist comment — only `recently-used.service.ts` and the S-021 auth file are allowed:

```ts
// shared/ui/recency/recently-used.service.ts
/* eslint-disable no-restricted-globals, no-restricted-syntax --
   Allowlisted by S-008 design; per-user recency cache is a deliberate localStorage consumer.
   See next/web/CLAUDE.md and S-002 lint rules. */
```

The recently-used list is read on `nzOpenChange` (dropdown open) to keep render cheap; the top group renders inside `nzDropdownRender` above the standard option list.

### Other primitives

- **`<af-button>`** wraps `nz-button`. Forwards `[nzType]`, `[nzDanger]`, `[nzLoading]`, `[disabled]`. Touch-target enforced via the global CSS rule above (chosen over `:host ::ng-deep` to avoid scoped overrides per primitive).
- **`<af-input>`** is a *native* `<input>` (AC-DIR-9), not `nz-input`. It exists to apply density classes + standard error styling via the surrounding `<af-form-field>`. Encourages `type="time" | type="date" | inputmode="numeric"` per AC-DIR-9 — documented in the kit README.
- **`<af-form-field>`** wraps `nz-form-item` + `nz-form-label` + `nz-form-control`. Projects label, native input, and `<af-field-errors>` into the right slots. Consumed by S-007 as the canonical form atom.
- **`<af-field-errors>`** reads `FormControl.errors` via injection of `NgControl` (or `formControlName` input), maps error keys → translation keys via transloco, renders inside `nz-form-control`'s `nzErrorTip`.
- **`<af-select>`** wraps `nz-select`. Defaults `[nzShowSearch]="true"` (searchable lists are the common case for reference data).
- **`<af-date-picker>`** exposes two modes — **range** (`mode="range"`, the load-bearing case from operator) wraps `nz-range-picker`; **single** (`mode="single"`) wraps `nz-date-picker`. Single API surface so S-062c consumes one component.
- **`<af-nav-bar>`** picks `nz-menu` + `nz-layout-sider` (hub-and-spoke is the FLS shape — left rail at `≥md`, hamburger drawer at `<md`). `nz-tabs` rejected — flight-edit accordion + reservation calendars don't naturally tab.
- **`<af-density-provider>`** as specified above.

JIT primitives (deferred): `<af-icon>`, `<af-badge>`, `<af-search-input>`, `<af-dialog>`, `<af-sticky-bar>`, `<af-accordion-section>`, `<af-time-now-button>`. Each lands when its consumer story is implemented; this story carries only the folder-stub `README.md` describing the intended wrapper.

### Build + config touch

- **`docker-compose.yml`:** no change.
- **Backend:** no change.
- **`package.json`:** add `ng-zorro-antd`. **Schematic vs manual call:** prefer the schematic (`ng add ng-zorro-antd`) if the published version declares Angular 21 peer compatibility. If it does not (Context7 latest is v19.x as of the operator lookup), install manually: pin a known-good version, register `provideNzI18n(de_DE)` in `app.config.ts`, and import only `ng-zorro-antd.variable.min.css` (skip the schematic's icon registration — `<af-icon>` ships lazy when needed). Surface the choice as Q1 in `## Open design questions`.
- **`src/styles.css`:** see snippet above — `@import 'tailwindcss'` already present, add ng-zorro variable CSS import, breakpoint tokens, density-scoped tokens, `--ant-*` bridge, density touch-target rule.
- **`eslint.config.mjs`:** add to the existing `*.ts` rule block:

  ```ts
  'no-restricted-imports': ['error', {
    paths: [
      { name: 'ng-zorro-antd', message: 'Import per-component entry points (e.g. ng-zorro-antd/button), not the umbrella module. See S-008 design.' },
    ],
    patterns: [
      { group: ['**/shared/ui/molecules/**', '**/shared/ui/organisms/**'],
        message: 'Atoms must not import molecules or organisms. See next/web/CLAUDE.md §1.' },
    ],
  }]
  ```
  Plus override blocks scoped to `shared/ui/atoms/**` (bans molecules + organisms imports) and `shared/ui/molecules/**` (bans organisms imports).
- **`angular.json`:** no change unless `ng add` rewrites `styles` array — review the schematic diff if used.

### Showcase page (AC-DIR-10)

Plain Angular route at `/dev/primitives` (`next/web/src/app/dev/primitives/primitives-showcase.page.ts`). One sub-page per primitive, each mounting the primitive in isolation. **Not Storybook** — Storybook is overkill for a solo team and adds a parallel build pipeline. Playwright drives the snapshot test at four viewport widths (`sm=360x640`, `md=768x1024`, `lg=1280x800`, `xl=1920x1080`) as a separate CI project `--project=primitives-snapshots`, isolated from the default e2e matrix to avoid baseline flake blocking PRs.

### a11y baseline (AC-DIR-11)

Inherited from ng-zorro's WAI-ARIA implementation (audited at v19 line). Wrappers enforce:
- Visible focus ring at both densities (`comfortable`: 2px solid `var(--color-brand-500)`; `dense`: 1.5px solid `var(--color-brand-500)` — WCAG 2.4.7 ≥ 2px-or-sufficient-contrast satisfied via brand-500 vs surface contrast ratio ≥ 3:1).
- `nz-modal` and `nz-date-picker` already use CDK `Overlay` + focus trap under the hood.
- Axe-core check in Playwright at all four viewports per AC-DIR-10's project.

### Integration with other stories

**Inputs (depends_on / implicit):**
- S-002 — folder scaffold (`shared/ui/{atoms,molecules,organisms}/`), ESLint baseline, `app.config.ts` zoneless pin, transloco/`@angular/localize` provider stub.
- S-005 (i18n) — `LocaleService` defers the translation-library call to whatever S-005 picks; treat as a soft dep, ship `LocaleService` here with a typed seam.
- ADR 0017 — breakpoint set + density convention.
- ADR 0022 — directive 1 (walking-skeleton-only build) + directive 2 (no schema).

**Outputs (downstream consumers):**
- S-007 (Reactive Forms) — consumes `<af-form-field>`, `<af-field-errors>`, native `<input>` via `<af-input>`, `<af-select>`.
- S-021 (Angular OIDC) — consumes `<af-button>` for login/logout actions.
- S-047 (Reference data list) — first real consumer of `<af-data-table>` (row mode at `≥md`).
- S-049 (Locations CRUD) — full kit consumer; `<af-autocomplete primitiveKey="country">` is the first recency-bias autocomplete site.
- S-062b (Flight list) — `<af-data-table>` card-mode at `<md`.
- S-062c (Flight edit form) — heaviest consumer: `<af-date-picker mode="range">`, `<af-autocomplete>` × multiple, `<af-accordion-section>` (JIT-land here), `<af-sticky-bar>` (JIT-land here), `<af-time-now-button>` (JIT-land here).

### Alternatives considered

- **Option A (chosen): ng-zorro-antd primary + Tailwind v4 tokens.** Operator-decided 2026-05-17; from-to range picker + searchable autocomplete with dropdown render are first-class.
- Option B: Tailwind + Angular CDK headless. Rejected — would require scratch-implementing the range picker and recency autocomplete, the two organisms whose effort decided the fork.
- Option C: Spartan UI. Rejected — coverage gap on data-table and range picker.
- Option D: Angular Material. Rejected — visual-language conflict with the chosen Ant Design surface.
- Option E: PrimeNG. Rejected — heavier theme footprint, OnPush story weaker than ng-zorro v19+.

### Per ADR 0022 directive 1

Walking-skeleton-only build today (10 primitives + 4 services). JIT-deferred primitives ship in their first consumer story; over-engineering the kit is a known trap.

### Per ADR 0022 directive 2

Zero schema / migration touch. Frontend-only story.

### Proposed ADR amendments

- **ADR 0004:** Add ng-zorro-antd to the named primitive-library options (currently lists Tailwind+CDK, Spartan UI, Angular Material, PrimeNG only). Operator decision: amend in a doc-only commit or batch with the next ADR-touching story.

## Edge cases & hidden requirements

### Fork-pick hidden requirements

- **ng-zorro Angular 21 peer-dep unverified:** ng-zorro's latest Context7-indexed release is 19.3.1 (Angular 19 line). Before implementation begins, its `package.json` `peerDependencies` must explicitly allow Angular 21 — or a fork/patch must be planned. This is a hard gate, not a soft preference. See `## Open design questions` Q1.
- **ng-zorro Zoneless + OnPush compatibility:** ng-zorro components must work without `zone.js` because `provideZonelessChangeDetection()` is committed in `app.config.ts`. If any ng-zorro component still triggers zone-based CD internally, it silently breaks under zoneless. Must be verified per-component before committing to the library.
- **ng-zorro tree-shakable imports:** standalone import model (e.g., `import { NzButtonModule } from 'ng-zorro-antd/button'`) is required to keep bundle budgets viable. Bulk `NgZorroAntdModule` imports are not acceptable — AC already implies per-primitive budget discipline per S-002's 500 KB hard error (`angular.json`).
- **Angular CDK + Tailwind path viability (rejected option):** CDK is confirmed Angular-version-matched (ships with Angular), but building all 13+ primitives from scratch on CDK would have been materially more effort than wrapping ng-zorro.

### Theming conflict

- **CSS custom-property namespace collision:** Tailwind v4's `@theme` block emits `--color-*`, `--font-*`, `--spacing-*` etc. ng-zorro's CSS-variable theming emits `--ant-*` prefixed variables. The namespaces don't overlap by default, but if a Tailwind token is renamed without updating the `--ant-*` bridge block, theming drifts silently. The operator's "change brand color in one place" expectation is encoded: Tailwind tokens are authoritative, the `--ant-*` block in `styles.css` derives from them. Document in `shared/ui/README.md` — ambiguity here produces invisible drift across primitives.
- **ng-zorro "Compact" theme is a build-time separate stylesheet, not a runtime attribute:** AC-DIR-2 requires `data-density="comfortable|dense"` to work as a runtime per-subtree toggle. ng-zorro's Compact mode ships as an alternate CSS bundle (`ng-zorro-antd/ng-zorro-antd.compact.min.css`); it is NOT used. Density is solved via Tailwind density-scoped tokens (`[data-density="dense"] { ... }`) AND `[nzSize]="density() === 'dense' ? 'small' : 'default'"` propagated through `<af-density-provider>`/`DensityService`. The `<af-density-provider>` directive must write BOTH the host attribute AND the service signal — signal-only updates miss the Tailwind side.

### Selector prefix drift

- **`<fls-...>` in AC text vs. `af-` in `next/web/CLAUDE.md` §6:** every AC in the S-008 frontmatter and task list uses `<fls-button>`, `<fls-input>`, etc. `next/web/CLAUDE.md` §6 (and §1's component catalog) uses `<af-button>`, `<af-form-field>`, etc. post-rebrand. The kit MUST use `af-` per §6 — AC text is stale. See `## Open design questions` Q2.
- **`app.component.ts` shell references:** when S-008 ships `<af-nav-bar>`, any `<fls-nav-bar/>` reference left over from S-002 will break unless updated atomically in the same PR. Hidden coupling — implementer must grep before merging.

### i18n surface

- **Two i18n stacks, one UX:** ng-zorro ships its own locale strings (date-picker month names, table pagination labels, etc.) via `NzI18nModule` with locale objects (`de_DE`, `fr_FR`, `it_IT`, `en_US`). S-005 introduces the app-level i18n library for AlpenFlight's own strings. `LocaleService` (defined in this story) is the single switch — call it once, both libraries flip in lockstep. Document the boundary in `shared/ui/README.md`.
- **Swiss German locale (`de-CH`) ≠ `de_DE` in ng-zorro:** date formats, number formats, and some UI strings differ. ng-zorro's `de_DE` uses `DD.MM.YYYY` by default (matches legacy pikaday `format = "DD.MM.YYYY"` in `flsweb/src/core/directives/datePicker/DatePickerInputDirective.js:4`), but this must be verified. If `de_DE` is wrong, a custom locale object is required.

### RTL support

- **No RTL today, but the choice locks future capability:** vision §C21 names de/fr/it/en (all LTR); no RTL locales are in scope. ng-zorro supports RTL via `[dir="rtl"]`; Tailwind v4 has logical-property utilities (`ps-`, `pe-`, etc.). No-op today, but worth a hidden-capability ledger entry.

### Density mode mechanism

- **`data-density` as a runtime per-subtree attribute (AC-DIR-2) requires both signal AND attribute:** the directive must propagate the signal to consuming wrappers (which switch `[nzSize]`) AND set the DOM attribute (which switches Tailwind density-scoped tokens). A signal-only update misses the Tailwind side; an attribute-only update misses the ng-zorro side. Reconciliation: directive writes both.

### Card-mode for data-table below `md` (AC-DIR-4)

- **`<nz-table>` has no card-mode out of the box.** The `<af-data-table>` wrapper provides it via a `@if (isCardMode())` template branch with `[primary] / [secondary] / [meta]` slot projections. This is a non-trivial custom build (not a thin wrap) — budget accordingly in the implement phase.
- **Legacy `ng-table` persists filter/sort settings per-table in `localStorage`** via `TableSettingsCacheFactory` (`flsweb/src/flights/FlightsController.js:49,83`). The new `<af-data-table>` + NgRx Signal Store must have an equivalent per-table persistence mechanism (keyed by route/table ID) — deferred to first real list consumer (S-047), not S-008.

### Touch-target lint (AC-DIR-3)

- **ng-zorro default button height is 40px (`nzSize="default"`):** WCAG 2.5.5 and Apple HIG require 44 × 44 px on mobile. 40 px misses by 4 px. The global density rule in `styles.css` (`[data-density="comfortable"] .ant-btn { min-height: 44px; }`) closes the gap. Confirm not re-broken in dense mode (28 × 28 px target there).

### Native input types preferred (AC-DIR-9)

- **ng-zorro replaces `<input type="date">` and `<input type="time">` with custom JS widgets by default:** `<nz-date-picker>` and `<nz-time-picker>` are fully custom, not wrappers around native inputs. AC-DIR-9 says native input types are preferred. The kit pins which primitives use native vs ng-zorro pickers:
  - `<af-input type="time" | "date">` → native `<input>` (no ng-zorro picker).
  - `<af-date-picker mode="range">` → `nz-range-picker` (the load-bearing from-to use case from operator).
  - `<af-date-picker mode="single">` → `nz-date-picker` (richer than native for the flight-form use case).
  - Native single-date inputs available via `<af-input type="date">` for simple forms.

### Recency-bias autocomplete (AC-DIR-5)

- **Single vs multi-select:** legacy selectize uses `maxItems: 1` single-select for flight-form dropdowns (`flsweb/src/flights/flight-edit-glider-form.html`), but accounting-rule filters use multi-value (`flsweb/src/masterdata/accountingRules/accountingRuleFilters-edit.html`). `<af-autocomplete>` must support both — `nz-select` handles both natively via `[nzMode]="'default' | 'multiple'"`.
- **Multi-field fuzzy search:** legacy selectize supports `searchField: ['Firstname', 'Lastname', 'City']`. `<af-autocomplete>` exposes this via the `[searchFields]` input + a custom filter function. Pure-function tested in vitest at 200 items × 3 fields < 5 ms.
- **`RecentlyUsedService` is `localStorage`-backed:** writes are banned in app code by the S-002 ESLint rule (`no-restricted-globals`). The service is the deliberate allowlist entry — uses an inline `eslint-disable` comment with rationale.

### Public-flow / SSR / bundle size on 3G

- **ng-zorro per-component gzipped cost:** standalone imports are ~5–15 KB each. Walking-skeleton primitive set (~10 ng-zorro modules) could add ~80–120 KB gzipped on top of the Angular baseline. The S-002 initial-bundle 500 KB hard error is the gate. Vision §F12 (mobile-first marginal-3G budget) is sensitive — capture exact baseline in `## Review` after the kit lands.

### Schematics + upgrade cadence

- **ng-zorro ships `ng add` schematics and `ng update` migrations:** Angular releases every 6 months; ng-zorro historically lags 1–2 releases on `ng update` support. Choosing ng-zorro adds a dependency on ng-zorro's release cadence on top of Angular's own cadence. For a solo operator, this is a steady-state maintenance cost.

### Licenses

- **License check: all options are MIT** — ng-zorro-antd is MIT; Angular CDK is MIT; Tailwind CSS is MIT. No AGPL, no GPL, no commercial-use restriction. Cleared.

### Showcase page (AC-DIR-10)

- **Storybook is heavyweight for this team scale:** AC-DIR-10 specifies a dev route at `next/web/src/app/dev/primitives/` (confirmed in the AC text). The Playwright snapshot test runs as a separate CI project `--project=primitives-snapshots`, isolated from the default e2e matrix to avoid baseline flake blocking PRs.

### a11y at both densities (AC-DIR-11)

- **WCAG 2.4.7 focus-visible in dense mode:** ng-zorro's default focus ring uses `outline: 2px solid #1677ff`. At 2 px this meets the ≥ 2 px threshold, but dense mode's tighter spacing can cause the focus ring to overlap adjacent controls. Override in the wrapper if visual review reveals overlap.
- **Color-only state indication in ng-zorro:** several ng-zorro components convey validity state via color only. `next/web/CLAUDE.md` §5 rule "never convey state by color alone" applies — wrappers must add an icon or aria attribute alongside color.

### Legacy feature surface gaps

- **Pikaday used `hourOfDay` attribute to pin time-of-day on date selection** (`flsweb/src/core/directives/datePicker/DatePickerInputDirective.js`). `<af-date-picker>` documents whether it emits a date-only or datetime value, and how the consumer controls the time component — otherwise date-stored-as-midnight-UTC bugs silently recur.
- **`<af-density-provider>` is a new primitive not in the original AC list:** it is implied by AC-DIR-2 but not enumerated. Walking-skeleton set includes it.

## Security plan

(N/A — frontend primitives story; no auth/PII/audit surface. Tenancy isolation is server-side (ADR 0008 — S-022) and stores enforce `clubId` claim via `@TenantId`; primitives are tenant-agnostic. The only client-side data persistence is `RecentlyUsedService`'s per-user recency cache in `localStorage` — covered by S-002's ESLint `no-restricted-globals` allowlist discipline. Component-level a11y baseline lives in `## Test plan` and `next/web/CLAUDE.md` §5.)

## Test plan

### Test pyramid for this story
- Unit (Vitest): 15 specs — services (`DensityService`, `ViewportService`, `RecentlyUsedService`, `LocaleService`) + pure component logic (`af-field-errors` error-key mapper, `af-autocomplete` fuzzy-filter function, `af-data-table` sort/page output signals). No `createComponent` DOM assertions per `next/web/CLAUDE.md` §8.
- Integration: (none) — no backend, no repository layer in this story.
- E2E (Playwright): 17 specs across 4 viewport widths — primitives showcase mount, per-primitive smoke, touch-target physical-size, card/row mode switch, density attribute propagation, axe-core a11y scan, focus-ring contrast, locale lock-step. New file `next/web/e2e/tests/primitives/showcase.spec.ts`; new Playwright project `primitives-snapshots`.
- Parity: (none) — `parity_test: none`. Greenfield; no legacy oracle.

### Unit tests

- `DensityService – derives 'dense' when ViewportService.isAtLeast('lg') is true`: mock viewport signal `true`, assert `density()` returns `'dense'`.
- `DensityService – derives 'comfortable' when viewport is below lg`: mock `false`, assert `density()` returns `'comfortable'`.
- `DensityService – setOverride('dense') wins over viewport-derived value`.
- `DensityService – clearOverride() restores viewport-derived value`.
- `ViewportService – isBelow('md') updates when MediaQueryList fires change event`.
- `ViewportService – isAtLeast('lg') initial-state false`.
- `RecentlyUsedService – record(key, id) returns the id within the 7-day window`.
- `RecentlyUsedService – recent() excludes entries older than window` (record at `Date.now() - 8d`, assert empty at 7d).
- `RecentlyUsedService – partitions by primitiveKey`.
- `RecentlyUsedService – evicts oldest when count exceeds 50` (capacity cap, LRU).
- `LocaleService – set('de') invokes nzI18n.setLocale(de_DE) AND translate.setActiveLang('de') AND sets document.documentElement.lang`.
- `LocaleService – set with unknown locale token` (pin behavior — throw or no-op — and assert deterministically).
- `af-field-errors – maps FormControl errors to translation keys` (`required`, `minlength`, `pattern`, `email` fixtures).
- `af-autocomplete – fuzzy filter matches across multiple searchFields`: 200×3 fixture; pure-function test; budget < 5 ms (perf assertion).
- `af-data-table – sortChange output emits {key, direction} on onColumnClick()`: call method, assert output spy receives asc then desc.

### Playwright (DOM + a11y)

All in `next/web/e2e/tests/primitives/showcase.spec.ts` under new project `primitives-snapshots`.

- `showcase route resolves at /dev/primitives` (all 4 viewports).
- `af-button smoke – renders, keyboard-reachable, has accessible name` (all 4).
- `af-input smoke – same shape` (all 4).
- `af-select smoke – same shape` (all 4).
- `af-form-field – label, input, and error region wire correctly` (md).
- `af-button touch target ≥ 44×44 px in comfortable density (sm viewport)` — `getBoundingClientRect()` assertion.
- `af-button touch target ≥ 28×28 px (icon-only) in dense density (sm)`.
- `af-select + af-input + af-autocomplete meet comfortable-density touch target (sm)`.
- `af-data-table renders rows at md+ and cards below md`.
- `af-data-table card-mode projects [primary], [secondary], [meta] slots`.
- `af-autocomplete – recently-used group appears after first selection`.
- `af-density-provider – sets data-density attribute on host AND subtree-override honored`.
- `af-nav-bar – collapses to drawer below md; left rail at md+`.
- `af-date-picker – range mode opens two panes; single mode opens one`.
- `Showcase page passes axe-core scan at sm, md, lg, xl` — zero serious / critical violations; document any known ng-zorro exclusions in `axe-known-issues.md`.
- `Focus ring is visible at both densities` — keyboard-tab, screenshot, assert outline width ≥ 1.5 px + contrast ≥ 3:1.
- `ng-zorro locale switches in lockstep with app locale` — `LocaleService.set('fr')`, assert ng-zorro "Today" label is French AND a transloco-rendered text is French.

### Fixtures

- **Playwright project `primitives-snapshots`**: separate from default `e2e`; 4 named viewport projects (`sm-360`, `md-768`, `lg-1280`, `xl-1920`). Chromium only. Snapshot baselines pinned per-OS (linux runner). `maxDiffPixelRatio: 0.001`.
- **`RecentlyUsedService` test fixture**: `beforeEach` clears `localStorage.removeItem('af.recently-used.v1')`.
- **Vitest mocks**: `NzI18nService`, `TranslocoService`, `ViewportService` (writable mock signal). No backend mocks needed.
- **Showcase route at `/dev/primitives`**: `data: { publicAccess: true }` so `SessionStore` doesn't redirect.

### Coverage gaps (deferred)

- Full e2e flows consuming kit — S-007 (Reactive Forms), S-047, S-049, S-062c.
- `af-accordion-section` / `af-sticky-bar` / `af-time-now-button` / `af-dialog` / `af-icon` / `af-badge` / `af-search-input` — JIT-deferred to first consumer story.
- Visual regression diff threshold tuning — refine after 2+ CI runs establish stable baselines.
- RTL — no RTL locale in scope.
- `localStorage` allowlist CI gate (fails when a new file adds the override comment without review note) — deferred to S-021 or next localStorage-touching story.

### Risks

- **ng-zorro Angular 21 peer-dep gap (Q1):** if v19.x doesn't run on Angular 21 cleanly, the entire suite is vacuous. Mitigation: smoke-test ng-zorro import at the very start of implement phase; escalate immediately on failure.
- **Playwright snapshot flake across runners**: font hinting varies. Mitigation: pin OS / chromium version; `maxDiffPixelRatio: 0.001`.
- **axe-core false positives on ng-zorro internals**: maintain `axe-known-issues.md`, revisit on every ng-zorro upgrade.
- **Density override propagation (signal vs attribute)**: directive must write both; vitest + Playwright assert both sides.
- **`localStorage` ESLint allowlist drift**: future stories adding `localStorage` consumers must add an allowlist comment; deferred CI gate catches un-reviewed additions.
- **Zoneless + signal timing in vitest**: `TestBed.flushEffects()` may be required after signal mutations — follow `session.store.spec.ts` pattern.

### Parity strategy

`parity_test: none`. Greenfield convention story; no legacy primitive oracle.

## Performance plan

### Hot paths

- **Initial bundle (cold first paint):** walking-skeleton primitive ng-zorro imports add to initial or lazy chunks. Eager (app shell): `af-nav-bar`, `af-button`, `af-input`, `af-form-field`, icon registry. Lazy: `af-data-table`, `af-date-picker`, `af-autocomplete`, `af-select`. Budget: total initial chunk ≤ 500 KB gzipped (S-002 hard error). Capture post-S-008 baseline in `## Review`.
- **App-shell first paint (`af-nav-bar` mount):** `nz-layout-sider` + `nz-menu` mount on shell bootstrap. Budget: < 50 ms on mid-range laptop, zoneless + OnPush.
- **`<af-data-table>` row-mode render at 100+ rows:** wraps `nz-table`; `[virtualScroll]` opt-in input pinned now (no-op until S-047). Budget: first contentful render < 100 ms at 100 rows.
- **`<af-data-table>` card-mode render at viewport `<md`:** budget < 150 ms at 50 cards on mid-range mobile.
- **`<af-autocomplete>` dropdown open + recency-group filter:** recency lookup p95 < 5 ms at 50 entries; fuzzy filter p95 < 5 ms at 200 items × 3 searchFields; `[debounceMs]` defaults to 150 ms.
- **`<af-date-picker>` range-mode panel open:** p95 < 50 ms.
- **Density-flip on viewport crossing (`md` → `lg`):** signal propagation < 16 ms (one frame).

### Required indexes
(N/A — frontend story, no DB queries.)

### N+1 risks
(N/A in this story.) **Flag for downstream (S-047 reference-data lists, S-062c crew picker):** `<af-autocomplete>` filter is O(N × M); pin `[debounceMs]=150` default and document the budget.

### Caching

- **`RecentlyUsedService`:** hydrated from `localStorage` once at construction; subsequent ops via signal. No JSON.parse on hot path. Bounded 50 entries per key, LRU eviction.
- **`ViewportService`:** four `MediaQueryList` listeners (sm/md/lg/xl); no polling, no resize listener.
- **`DensityService`:** `computed()` off `ViewportService` + override; signal-cached.
- **ng-zorro locale registry:** `provideNzI18n(de_DE)` at root; `setLocale(...)` swaps at runtime; locales tree-shake to the four configured (`de_DE`, `fr_FR`, `it_IT`, `en_US`).
- **ng-zorro icon registry:** explicit icon-set registration in `app.config.ts`; not the full `@ant-design/icons-angular` bundle.

### Latency budget

- **Initial bundle gzipped (post-S-008):** ≤ 500 KB total (S-002 hard error). Expected ng-zorro tax: ~80–120 KB across walking-skeleton primitives + `ng-zorro-antd.variable.min.css`.
- **`ng-zorro-antd.variable.min.css`:** ~30–50 KB gzipped (CSS-variable variant; smaller than the ~80 KB default Less-compiled bundle).
- **Shell first paint:** p95 < 50 ms.
- **`<af-data-table>` row-mode render:** p95 < 100 ms at 100 rows; virtual-scroll seam ready for >500-row lists.
- **`<af-data-table>` card-mode render:** p95 < 150 ms at 50 cards.
- **`<af-autocomplete>` fuzzy filter:** p95 < 5 ms at 200 items × 3 searchFields.
- **`<af-autocomplete>` recency lookup:** p95 < 5 ms at 50-entry per-key cache.
- **`<af-date-picker>` panel open:** p95 < 50 ms (range mode).
- **Density-flip propagation:** < 16 ms.
- **Mobile-first marginal-3G initial paint (Vision §F12):** target < 80 KB JS per route after lazy splitting. Real measurement deferred to S-108.

### Memory

- Walking-skeleton kit at idle: ~5–10 MB JS heap. Acceptable.
- `RecentlyUsedService` cache: ~4 KB per primitiveKey × ≤ 10 keys ≈ 40 KB. Trivial.
- `ViewportService` listeners: 4 MQL subscriptions; negligible.
- `<af-data-table>` row-mode at 1000 rows (deferred to S-047): ~500 KB JS heap; opt into virtual scroll if >5000 rows.
- Playwright snapshot fixtures: O(N×4) PNGs scoped to `/dev/primitives`.

### Performance test plan

- **Bundle-size baseline capture (lands in S-008):** `ng build --configuration=production --stats-json` + esbuild metafile analysis post-S-008. Record initial-chunk gzipped + per-ng-zorro-module contribution in `## Review`.
- **`<af-autocomplete>` fuzzy-filter benchmark (vitest):** 200×3 fixture, median of 100 runs < 5 ms.
- **`RecentlyUsedService` read benchmark (vitest):** 50-entry pre-seeded, median of 100 runs < 5 ms.
- **Density-flip propagation (vitest):** signal-only test; all consumers reflect new `density()` within one micro-task.
- **`<af-data-table>` render benchmark:** deferred to S-047 with real data.
- **Bundle-size CI gate:** deferred to S-108.
- **Lighthouse / Core Web Vitals / p99:** deferred to S-108.

### Risks

- **ng-zorro initial-bundle tax may breach the 500 KB hard error.** Options if breached: (a) lazy-chunk `nz-date-picker` per route, (b) replace `af-nav-bar` with a Tailwind-only nav for the walking skeleton, (c) escalate to operator to relax the budget. Mitigation: measure first; decide post-measurement.
- **`ng-zorro-antd.variable.min.css` cascade specificity:** deeper-scope `--ant-*` overrides may surprise. Document in kit README: brand tokens are root-only.
- **`<af-data-table>` row-mode at >1000 rows without virtual scroll:** budget breach risk for downstream consumers. Mitigation: pin `[virtualScroll]` input; measure at S-047.
- **Mobile 3G initial paint (Vision §F12):** SHOULD-pass risk if walking-skeleton primitives go eager. Mitigation: review eager-vs-lazy split during implement; real measurement deferred to S-108.
- **Snapshot-test CI wall time:** separate project + parallel shard; scope to dev showcase page only.
- **ng-zorro v19 vs Angular 21 peer mismatch (Q1):** perf results against a forced/patched version may not reflect production. Mitigation: re-baseline in `## Review` if a different version lands.

### Out of scope (deferred)

- Bundle-size CI gate → S-108.
- Real bootstrap-latency against live backend → S-047 + S-062c.
- Service-worker caching → S-117 (PWA).
- Lighthouse / Core Web Vitals → S-108.
- p99 sustained-load → S-108.

## Open design questions

- **Q1 — ng-zorro Angular 21 peer compatibility.** Latest Context7-indexed ng-zorro is v19.3.1 (Angular 19 line). At implement time, verify `peerDependencies` allow Angular 21. If no compatible release exists: (a) wait for upstream, (b) pin a community fork, (c) override peer-deps + smoke-test, (d) fall back to a Tailwind+CDK build for the walking skeleton only. Operator decides at implement-phase start.
- **Q2 — AC selector-prefix drift.** Story ACs say `<fls-button>` etc.; refined design uses `<af-button>` per `next/web/CLAUDE.md` §6 post-rebrand. Operator decides whether to amend ACs to `<af-*>` (and replace the "Tailwind + Angular CDK (headless, recommended)" line with "ng-zorro-antd + Tailwind v4 tokens (chosen)") via `/modernize-decompose` or by a direct frontmatter edit.

<!-- modernize-refine: end -->

## Notes

The legacy app leans on `ng-table` + `selectize` + `pikaday`. Matching their feature surface (paged, sortable, filterable tables; tag-style selects; date picker) is the bar — ng-zorro's `nz-table` + `nz-select[nzShowSearch]` + `nz-range-picker`/`nz-date-picker` cover this directly.

Keep each primitive's public API small. Extend on demand from feature stories — over-spec'd primitives are a known trap (legacy R10 was effectively this).
