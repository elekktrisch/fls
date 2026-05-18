---
id: S-048
title: Clubs CRUD walking-skeleton (mocked auth)
epic: E-06
status: done
started_at: 2026-05-17
done_at: 2026-05-18
depends_on: [S-008]
acceptance:
  - End-to-end vertical slice: Angular SPA → Spring REST → Postgres `club` table. `docker compose --profile next up` + `pnpm start` produces a working Clubs list + edit form at `/clubs` in a browser.
  - **Mock authorization (this story only):** Spring profile `mock-auth` activates a `MockSecurityConfig` that injects a hard-coded principal (`{ sub: "mock-sysadmin", clubId: "club-1", roles: ["SYSTEM_ADMINISTRATOR"] }`) on every request — bypasses the OAuth2 resource server chain. SPA `MockAuthInterceptor` stamps a placeholder `Authorization: Bearer mock-sysadmin` header so HttpClient calls aren't rejected by interceptor middleware. Both pieces are profile-gated and explicitly marked for rip-out.
  - Backend: `Club` entity + repository + service + `@PreAuthorize`-gated REST controller + DTO. Uses Spring Security `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")` against the mock principal so the auth-chain seam is real (the predicate stays when S-019/S-020 land; only the principal source flips from `MockSecurityConfig` to JWT decoder).
  - DB migration: `V5__clubs_walking_skeleton.sql` adds `slug` + `public_registration_enabled` columns to the existing `club` table (V2 schema from S-012/S-013). No CHECK constraints, no triggers (per ADR 0022 directive 2).
  - Frontend: `ClubsStore` (NgRx Signal Store with `withEntities`, follows S-006 reference), Clubs list page using `<af-data-table>`, Clubs edit form using `<af-form-field>` + `<af-input>` + `<af-button>` from S-008.
  - Generated TS API client (orval, S-004) re-runs cleanly and the SPA consumes the generated `ClubsService`.
  - One Playwright happy-path e2e: navigate to /clubs, see list, edit one, save, see update reflected. Backend up via the compose `next` profile + `mock-auth`.
  - `club.slug` partial-unique-indexed (`WHERE slug IS NOT NULL`); create / update validate uniqueness server-side; UI surfaces 409 cleanly.
  - **Rip-out plan documented in `next/server/src/main/java/.../auth/MockSecurityConfig.java` Javadoc + the AC bullet here:** when S-019 (Keycloak) + S-020 (Spring resource server) + S-022 (TenantId) land, the `mock-auth` profile + the SPA `MockAuthInterceptor` get deleted in one commit; the `@PreAuthorize` predicates + Signal Stores + forms stay unchanged.
estimate: L
adr_refs: [0005, 0008, 0022]
parity_test: e2e/tests/masterdata/clubs-crud.spec.ts
parity_excluded:
  - Role-based access assertions (CLUB_ADMINISTRATOR own-club restriction, non-sysadmin 403) — mock principal is fixed SYSTEM_ADMINISTRATOR; re-enable at S-019/S-020 when role-switching is available.
  - Login/logout flow (legacy uses `/Token` password grant) — N/A under mock-auth; OIDC re-port at S-021.
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 56
github_pr: 57
reviewed: true
reviewed_at: 2026-05-18
review_outcome: improvements-only
review_parity_oracle: e2e/tests/masterdata/clubs-crud.spec.ts + ClubsControllerIT (testcontainer + mock-auth)
review_reviewers: [maintainability, security, parity, usability]
merged: true
merged_at: 2026-05-18
---

## Context

S-048 reshape (operator directive 2026-05-17, see `project-walking-skeleton-clubs-mocked-auth.md`): land Clubs CRUD as the **first end-to-end UI-to-database walking-skeleton**, with mocked authorization. The full auth chain (S-019 Keycloak + S-020 Spring resource server + S-022 TenantId resolver + S-026 authz model) stays deferred — when it lands, it replaces the mock without touching the domain code.

**Why:** validates the S-008 component primitives kit + S-006 Signal Store reference + S-004 generated TS client against a real domain, gives the operator a demoable user-facing slice ahead of the heavy auth-chain work, and surfaces real integration friction (DB ↔ JPA ↔ DTO ↔ generated client ↔ Signal Store ↔ form ↔ Playwright) that synthetic dev routes can't expose.

**Clubs are not `@TenantId`'d** — they ARE the tenant entity. Authorization is by role, not by tenant filter. Mock principal is system-admin so it can list all clubs.

**What stays real:**
- The Spring Security `@PreAuthorize` predicates + the configured `SecurityFilterChain` shape.
- The Signal Store + Reactive Forms + form-field/data-table consumption.
- The Playwright e2e suite hitting the real backend.
- The Postgres schema + migration discipline.

**What's mocked (and ripped out at S-019/S-020):**
- `MockSecurityConfig` (Spring profile `mock-auth`): hard-codes the authenticated principal on every request via an `OncePerRequestFilter` that builds a `JwtAuthenticationToken` matching the shape S-020's real decoder will produce.
- `MockAuthInterceptor` (Angular): stamps `Authorization: Bearer mock-sysadmin` so HttpClient flows look normal.
- `clubId` claim resolution: short-circuits to the mock principal's `clubId` field.

## Acceptance criteria
See frontmatter.

## Tasks
Superseded by acceptance criteria.

## Notes
Walking-skeleton, not production-ready. The mock-auth profile is dev-only; `MockSecurityConfig.forbidInProd()` (a `@PostConstruct` hook on the profile-gated bean) refuses to boot when `prod` is co-active. A real `application-prod.yml` ships when S-040 lands.

This story validates the S-008 component primitives kit against a real domain. The deferred axe-core / touch-target a11y Playwright suite from S-008 is *not* layered onto Clubs in this story — left for a dedicated a11y story.

## Implementation deviations from refined design

- **`ClubAwareJwtAuthenticationConverter` does not override `JwtAuthenticationConverter.convert(Jwt)`** (the method is `final` in Spring Security 7). Customization is via `setJwtGrantedAuthoritiesConverter(...)` in the constructor, which is the only seam Spring exposes. Behavior is identical to the design intent.
- **`ClubsStore` mutations are write-then-patch, not pre-emptive optimistic.** The store waits for the server response and then applies `addEntity` / `updateEntity` / `removeEntity`. Acceptable for the walking-skeleton; the "snapshot prev → optimistic patch → revert on error" template lives in `hello.store.ts` as a TODO for the first hot-write story.
- **`ClubsControllerIT` test-method names** use `_returns_` underscores rather than the `mockSysadmin` / `validPayload` prefixes the design plan listed. Coverage is equivalent — 8 ITs covering the 8 happy/edge/error rows in the test plan (list+seeded, create+valid, create+blank, create+409, update+ok, update+404, delete+204+then-404, list-excludes-soft-deleted).
- **`ClubsAuthorizationTest` uses full `@SpringBootTest` rather than `@WebMvcTest`** (the slice doesn't include Spring Security autoconfig under Boot 4 without extra plumbing). Same intent — proves the predicate gates a downgraded principal at 403.
- **No `MockPrincipalClaims.java` standalone record** — the Jwt is built inline in `MockAuthenticationFilter` (cleaner; fewer files in the rip-out directory). Rip-out checklist updated to reflect the actual 3-file directory.
- **`af-data-table` tracks by item identity** (S-008 default); the refinement notes acknowledged this as a known-trivial inefficiency. No change.
- **Hikari pool capped to 4 in `application-test.yml`** — added to keep the shared Postgres testcontainer from exhausting connections now that mock-auth + default-profile contexts both cache. Not in the original design but required to keep `./gradlew check` green.
- **Mock-auth `clubId` claim seeded with the V5 seed-row UUID** rather than the design's literal `"club-1"` — keeps `principal.claims['clubId']` a valid UUID string the way the real JWT will emit, even though the disjunctive predicate clause is never reached today (mock principal is sysadmin).

<!-- modernize-refine: start -->

## Design notes

### Module layout

#### Backend — `next/server/src/main/java/ch/alpenflight/`

New package `clubs/` (sits alongside `platform/hello/`; not under `platform/` because Clubs is a domain feature, not infra cross-cutting):

```
ch/alpenflight/clubs/
├── package-info.java
├── Club.java                    JPA entity (table: club). Aggregate root.
├── ClubsRepository.java         Spring Data JpaRepository<Club, UUID>.
├── ClubsService.java            Transactional service; slug-uniqueness check; domain validation.
├── ClubsController.java         REST @RestController, all endpoints @PreAuthorize-gated.
├── ClubDtos.java                Bag of records: ClubResponse / ClubCreateRequest / ClubUpdateRequest.
├── ClubMapper.java              Entity ↔ DTO. Plain static methods (no MapStruct for single entity).
└── SlugAlreadyExistsException.java   Domain exception → 409 via @ResponseStatus.
```

A package-local `ClubsExceptionHandler` is **not** added: `SlugAlreadyExistsException` is `@ResponseStatus(CONFLICT)`-annotated and Spring's `ResponseEntityExceptionHandler` covers validation errors. A global `@RestControllerAdvice` is a later concern (S-027 / S-040 territory).

New package `auth/` (mock seam — all files explicitly tagged for rip-out):

```
ch/alpenflight/auth/
├── package-info.java                Javadoc: "DELETE in S-019/S-020 land commit".
├── MockSecurityConfig.java          @Profile("mock-auth") @Configuration. Custom SecurityFilterChain.
├── MockAuthenticationFilter.java    OncePerRequestFilter — builds a JwtAuthenticationToken per request.
└── MockPrincipalClaims.java         Record { sub, clubId, roles } used to seed the fake Jwt.
```

Plus the converter the real chain will reuse, kept in a **stable** location (not under `auth/`, so it survives the rip-out):

```
ch/alpenflight/platform/security/
├── package-info.java
└── ClubAwareJwtAuthenticationConverter.java   Maps realm_access.roles → ROLE_* authorities; extracts clubId claim.
```

#### Frontend — `next/web/src/app/`

```
features/clubs/
├── clubs.routes.ts              CLUBS_ROUTES — list (default) + edit child.
├── clubs.store.ts               ClubsStore — Signal Store (withEntities).
├── list/
│   ├── clubs-list.page.ts       Standalone component. <af-data-table> consumer.
│   └── clubs-list.page.spec.ts  Vitest — store-driven logic only (per next/web/CLAUDE.md §8).
└── edit/
    ├── clubs-edit.page.ts       Standalone component. Reactive form. <af-form-field> + <af-input>.
    └── clubs-edit.page.spec.ts  Vitest — validators / mapping only.

core/auth/
├── mock-auth.interceptor.ts     HttpInterceptorFn. Stamps Authorization: Bearer mock-sysadmin on /api/v1/**.
├── mock-auth.bootstrap.ts       provideAppInitializer factory → SessionStore.login(MOCK_USER, 'club-1').
└── README.md                    "DELETE in S-019/S-020 land commit" rip-out checklist.

e2e/tests/clubs/
└── clubs-crud.spec.ts           Playwright happy path. Real backend via compose `next` + `mock-auth`.
```

`app.routes.ts` gains:
```ts
{ path: 'clubs', loadChildren: () => import('@features/clubs/clubs.routes').then(m => m.CLUBS_ROUTES), canActivate: [authGuard] }
```

`app.config.ts` (delta):
- `provideHttpClient(withFetch(), withInterceptors([mockAuthInterceptor]))`
- `provideAppInitializer(mockAuthBootstrap)` (Angular 19+ functional initializer).
- One-line comment on each new line citing the rip-out story.

#### DB — `next/server/src/main/resources/db/migration/`

Head is V4 (`V4__reservations_planning_accounting.sql`). **New: `V5__clubs_walking_skeleton.sql`.**

```sql
-- V5__clubs_walking_skeleton.sql
--
-- S-048 walking-skeleton: adds the columns S-048's domain validation needs.
-- Per ADR 0022 directive 2: structural only — no CHECK on slug format,
-- no trigger; the aggregate enforces the rules.

ALTER TABLE club
    ADD COLUMN slug                         VARCHAR(64),
    ADD COLUMN public_registration_enabled  BOOLEAN NOT NULL DEFAULT false;

-- Partial unique index — slug is identity-bearing once populated, but the
-- column is added nullable to keep existing V2 fixture rows valid until a
-- backfill story lands. Identity-bearing partial UNIQUE is the one structural
-- constraint ADR 0022 directive 2 explicitly permits.
CREATE UNIQUE INDEX ux_club_slug ON club (slug) WHERE slug IS NOT NULL;
```

No CHECK on format. No trigger. Format validation (regex `^[a-z0-9-]{3,64}$`) lives on the `Club` aggregate.

### Domain model

`Club` (table `club`). NOT `@TenantId`-annotated — clubs ARE the tenant; authorization is by role, not by tenant filter.

```java
@Entity
@Table(name = "club")
public class Club {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)         // ADR 0019
    private UUID id;

    @Column(name = "clubname", nullable = false, length = 100)
    private String clubname;

    @Column(name = "club_key", nullable = false, length = 10)
    private String clubKey;                                  // legacy short code; identity-bearing (V2:172)

    @Column(name = "slug", length = 64)                      // new in V5; partial unique index
    private String slug;

    @Column(name = "public_registration_enabled", nullable = false)
    private boolean publicRegistrationEnabled;

    // ... existing V2 columns (country_id, club_state_id, contact info) stay mapped (so updates don't null them)
    // but are NOT in ClubResponse / ClubUpdateRequest. Subsequent stories grow the DTO.

    // Domain methods (per ADR 0022 directive 2):
    public void rename(String newName) { /* trim + validate not-blank */ }
    public void rebrand(String newSlug) { /* validate format ^[a-z0-9-]{3,64}$ */ }
    public void enablePublicRegistration() { ... }
    public void disablePublicRegistration() { ... }
}
```

Slug-uniqueness check is a service-layer pre-check + a defensive `try/catch DataIntegrityViolationException → SlugAlreadyExistsException`. The pre-check is a UX optimization; the unique index is the source of truth.

`country_id` + `club_state_id` are NOT NULL FKs on the existing `club` table — see Edge cases for the walking-skeleton handling (form scopes to seed values; full FK pickers land at S-047).

### API surface (per ADR 0005)

| Method | Path | `@PreAuthorize` | Request | Response | Status |
|---|---|---|---|---|---|
| GET    | `/api/v1/clubs`         | `hasRole('SYSTEM_ADMINISTRATOR')` | — | `ClubResponse[]` | 200 |
| GET    | `/api/v1/clubs/{id}`    | `hasRole('SYSTEM_ADMINISTRATOR') or (hasRole('CLUB_ADMINISTRATOR') and #id == authentication.principal.claims['clubId'])` | — | `ClubResponse` | 200 / 404 / 403 |
| POST   | `/api/v1/clubs`         | `hasRole('SYSTEM_ADMINISTRATOR')` | `ClubCreateRequest` | `ClubResponse` | 201 / 400 / 409 |
| PUT    | `/api/v1/clubs/{id}`    | `hasRole('SYSTEM_ADMINISTRATOR') or (hasRole('CLUB_ADMINISTRATOR') and #id == authentication.principal.claims['clubId'])` | `ClubUpdateRequest` | `ClubResponse` | 200 / 400 / 404 / 409 |
| DELETE | `/api/v1/clubs/{id}`    | `hasRole('SYSTEM_ADMINISTRATOR')` | — | — | 204 / 404 |

DTOs (Java records, immutable, Jackson-friendly):

```java
public record ClubResponse(
    UUID id,
    String name,
    String slug,
    String clubKey,
    boolean publicRegistrationEnabled
) {}

public record ClubCreateRequest(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
    @NotBlank @Size(max = 10) String clubKey,
    boolean publicRegistrationEnabled
) {}

public record ClubUpdateRequest(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
    boolean publicRegistrationEnabled
) {}
```

Bean Validation on the DTO + domain validation on the aggregate. The DTO layer is fast-fail; the aggregate is the structural invariant. (Belt-and-suspenders is intentional — DTO validation gives better 400 messages; aggregate survives if someone bypasses the DTO.)

`clubKey` is **not** updatable post-create — preserves the legacy invariant. Surface as a separate endpoint later if needed.

### Mock-auth wiring

#### Backend — `MockSecurityConfig` shape (Spring Security 7)

```java
@Configuration
@EnableMethodSecurity                     // turns on @PreAuthorize.
@Profile("mock-auth")                     // ONLY active when SPRING_PROFILES_ACTIVE=mock-auth.
class MockSecurityConfig {

    @Bean
    SecurityFilterChain mockFilterChain(
            HttpSecurity http,
            MockAuthenticationFilter mockFilter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)               // SPA + dev only.
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/actuator/health").permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(mockFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    MockAuthenticationFilter mockAuthenticationFilter(ClubAwareJwtAuthenticationConverter converter) {
        return new MockAuthenticationFilter(converter);
    }
}
```

```java
class MockAuthenticationFilter extends OncePerRequestFilter {

    // Mirror of the shape S-020's resource server will produce.
    private static final Jwt MOCK_JWT = Jwt.withTokenValue("mock-sysadmin")
        .header("alg", "none")
        .subject("mock-sysadmin")
        .claim("clubId", "club-1")
        .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR")))
        .issuedAt(Instant.EPOCH)
        .expiresAt(Instant.MAX)
        .build();

    private final ClubAwareJwtAuthenticationConverter converter;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        AbstractAuthenticationToken token = converter.convert(MOCK_JWT);
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
        try { chain.doFilter(req, res); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
```

`ClubAwareJwtAuthenticationConverter` (in `platform/security/`, kept past the rip-out) wraps Spring's `JwtAuthenticationConverter` with a `JwtGrantedAuthoritiesConverter` that:
- reads `realm_access.roles`
- prefixes each role with `ROLE_` (Spring's convention) so `hasRole('SYSTEM_ADMINISTRATOR')` works
- exposes `clubId` claim via the standard `Jwt.getClaim("clubId")` path that `@PreAuthorize("authentication.principal.claims['clubId']")` reads

**Choosing option (a)**: route the mock JWT through the real converter. Proves the converter shape under the mock, so S-020 just swaps `MockAuthenticationFilter` for `BearerTokenAuthenticationFilter` against a real decoder — no `@PreAuthorize` expressions move.

`application-mock-auth.yml`: empty overlay file with a one-line comment documenting the profile's intent. No values are config-driven; all mock state inline in the bean (intentional — keeps the rip-out surface to a single deletable directory).

#### Frontend — interceptor + bootstrap

```ts
// core/auth/mock-auth.interceptor.ts
// DELETE in S-019/S-020 land commit. See core/auth/README.md.
export const mockAuthInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.includes('/api/v1/')) return next(req);
  return next(req.clone({ setHeaders: { Authorization: 'Bearer mock-sysadmin' } }));
};
```

```ts
// core/auth/mock-auth.bootstrap.ts
// DELETE in S-019/S-020 land commit.
export const MOCK_USER: User = {
  id: 'mock-sysadmin', username: 'mock-sysadmin', email: 'mock@local',
  firstName: 'Mock', lastName: 'Sysadmin',
  clubId: 'club-1', roles: ['SYSTEM_ADMINISTRATOR'],
};
export function mockAuthBootstrap(): void {
  inject(SessionStore).login(MOCK_USER, 'club-1');
}
```

Wired in `app.config.ts` via `provideAppInitializer(mockAuthBootstrap)`. The authGuard from S-006 sees an authenticated user immediately on first navigation; `/clubs` resolves without an OIDC redirect.

**Note (boyscout):** `SessionStore.User.roles` currently uses `'CLUB_ADMIN' | 'SYSTEM_ADMIN' | 'MEMBER'`. Backend `@PreAuthorize` checks `SYSTEM_ADMINISTRATOR`. Align the SPA `User.roles` enum to `SYSTEM_ADMINISTRATOR` / `CLUB_ADMINISTRATOR` now (matches the server's authority string and saves a mapper at the S-021 OIDC boundary).

### Signal Store — `ClubsStore`

Shape modeled on `HelloStore` but **uses `withEntities`** (the deferred S-047 marker now lands at S-048):

```ts
type Club = ClubResponse;   // from @api/generated/model

interface ClubsExtraState {
  selectedId: string | null;
  isLoading: boolean;
  loadError: string | null;
  saveError: string | null;
  lastRefreshedAt: number | null;
}

export const ClubsStore = signalStore(
  { providedIn: 'root' },
  withEntities<Club>(),                                                 // ids, entityMap, entities()
  withState<ClubsExtraState>({
    selectedId: null, isLoading: false, loadError: null, saveError: null, lastRefreshedAt: null,
  }),
  withComputed(({ entities, loadError, saveError, selectedId }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedClub: computed(() => entities().find(c => c.id === selectedId()) ?? null),
  })),
  withMethods((store, clubsApi = inject(ClubsService), bus = inject(MUTATION_BUS)) => ({
    select(id: string | null): void { patchState(store, { selectedId: id }); },

    loadAll: rxMethod<void>(pipe(
      tap(() => patchState(store, { isLoading: true, loadError: null })),
      switchMap(() => clubsApi.listClubs().pipe(tapResponse({
        next: (cs) => patchState(store, setAllEntities(cs), { isLoading: false, lastRefreshedAt: Date.now() }),
        error: (e: HttpErrorResponse) => patchState(store, { loadError: e.message, isLoading: false }),
      }))),
    )),

    create: rxMethod<ClubCreateRequest>(pipe(
      tap(() => patchState(store, { saveError: null })),
      switchMap(req => clubsApi.createClub(req).pipe(tapResponse({
        next: (c) => { patchState(store, addEntity(c)); bus.next({ kind: 'club.created', id: c.id }); },
        error: (e: HttpErrorResponse) => patchState(store, { saveError: e.message }),
      }))),
    )),

    update: rxMethod<{ id: string; req: ClubUpdateRequest }>(pipe(
      tap(({ id, req }) => {
        const prev = store.entityMap()[id];
        patchState(store, updateEntity({ id, changes: { ...prev, ...req } }), { saveError: null });
      }),
      switchMap(({ id, req }) => clubsApi.updateClub(id, req).pipe(tapResponse({
        next: (c) => { patchState(store, updateEntity({ id, changes: c })); bus.next({ kind: 'club.updated', id }); },
        error: (e: HttpErrorResponse) => { patchState(store, { saveError: e.message }); store.loadAll(); },
      }))),
    )),

    delete: rxMethod<string>(/* analogous to update */),
  })),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.loadAll();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe(evt => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<Club>([]), { selectedId: null });
        }
      });
    },
  }),
);
```

Mutation-bus event-kind additions: `'club.created' | 'club.updated' | 'club.deleted'` — extend the `MutationEvent` discriminated union in `core/mutation-bus/mutation-bus.ts`.

### Frontend forms

**`clubs-list.page.ts`** — standalone component. Template uses `<af-data-table>`:
- `[items]="store.entities()"`
- Templates `#primary`/`#secondary`/`#meta` project name / slug / public-registration badge
- Row click → router.navigate to `/clubs/:id/edit`
- Toolbar button "New club" → `/clubs/new`

**`clubs-edit.page.ts`** — standalone. Reactive form:
```ts
form = inject(FormBuilder).group({
  name: ['', [Validators.required, Validators.maxLength(100)]],
  slug: ['', [Validators.required, Validators.pattern(/^[a-z0-9-]{3,64}$/)]],
  clubKey: ['', [Validators.required, Validators.maxLength(10)]],
  publicRegistrationEnabled: [false],
});
```
- Each control wrapped in `<af-form-field [label]="..." [for]="...">` + projected `<input id="..." formControlName="...">` (label-association convention from S-008).
- `<af-field-errors [errors]="form.controls.X.errors">` for inline errors.
- Save button calls `store.create(form.value)` or `store.update({ id, req: form.value })` based on route param.
- 409 from server → set `form.controls.slug.setErrors({ duplicate: true })`.

### Generated TS client (orval, S-004)

- New spec drop on backend build emits `clubs` schemas + operations into `next/openapi/openapi.yaml`.
- `pnpm generate-api` re-runs orval → `next/web/src/app/api/generated/clubs/clubs.service.ts` (`listClubs`, `getClub`, `createClub`, `updateClub`, `deleteClub`) + models in `next/web/src/app/api/generated/model/`.
- `ClubsStore` imports `ClubsService` from `@api/generated/clubs/clubs.service` and DTOs from `@api/generated/model`. No hand-written SPA-side service wrapper.

### Compose / build

- `docker-compose.yml` — the `next/server` service (verify it exists; see Open Questions) gets `SPRING_PROFILES_ACTIVE: "mock-auth"` under the `next` profile section. Production profile (when it lands at S-040) explicitly does NOT include `mock-auth`.
- `next/server/build.gradle.kts` — **add `spring-boot-starter-security` and `spring-boot-starter-data-jpa`** (currently neither is on the classpath; see Edge cases).
- `next/web/package.json` — no change.
- `next/web/eslint.config.mjs` — no change (consider adding `MockAuthInterceptor` import-scope rule in self-review).
- `application.yml` — no change.
- `application-mock-auth.yml` — **new**, empty overlay + Javadoc-style comment block.

### Per ADR 0022 directive 2

- Slug format: regex on aggregate, **NOT** a CHECK constraint.
- Slug case: enforce lowercase in `Club.rebrand()`, **NOT** a `GENERATED` column.
- `public_registration_enabled` default: column-level `DEFAULT false` is structural (default value, not a business rule) — kept.
- Soft-delete (`deleted_on`): existing V2 column, untouched. Whether clubs participate in soft-delete is a later concern.

If a reviewer proposes a CHECK on slug format or a trigger to enforce case, that is a deviation requiring an ADR amendment.

### Integration with other stories

**Inputs:**
- `<af-form-field>`, `<af-input>`, `<af-button>`, `<af-data-table>`, `<af-field-errors>` from **S-008** UI kit.
- `HelloStore` shape reference from **S-006** (`next/web/src/app/features/hello/hello.store.ts`).
- `SessionStore` + `authGuard` + `MUTATION_BUS` from **S-006** (`next/web/src/app/core/`).
- orval pipeline from **S-004** (`pnpm generate-api`).
- OpenAPI publishing from **S-003** (`springdoc-openapi`).
- Flyway baseline V1–V4 from **S-009 / S-012 / S-013 / S-014**.

**Outputs (consumed downstream):**
- `MockSecurityConfig` + `MockAuthenticationFilter` + `mockAuthInterceptor` + `mockAuthBootstrap`: **deleted** in one commit by the **S-019 / S-020 / S-022 / S-026** bundle. Predicates / Stores / forms / Playwright stay.
- `ClubAwareJwtAuthenticationConverter` (in `platform/security/`): **consumed** by S-020 as the converter against the real `JwtDecoder`.
- `ClubsStore` shape (`withEntities` + optimistic update + bus subscription): **template** for **S-049** Locations CRUD (first `@TenantId`-aware entity), **S-050+** for further masterdata.
- `Club` aggregate + `slug` column: consumed by **S-051** Persons + PersonClub (needs `clubId` as FK target).
- `'club.*'` mutation-bus events: consumed by **S-027** audit log.
- `provideAppInitializer` pattern: replaced — not consumed — by S-021's OIDC bootstrap.

### Alternatives considered

- **Option A (chosen): one-package mock seam (`auth/`) + reusable converter in `platform/security/`.** Single directory to delete; the converter that survives the rip-out is the same one the real chain uses, so the converter shape is exercised in dev from day one.
- **Option B: skip the mock, land the auth chain first.** Rejected by operator directive — getting an end-to-end UI-DB slice earlier delivers more signal. Saved as `project-walking-skeleton-clubs-mocked-auth.md`.
- **Option C: mock only the SPA side; backend accepts unauthenticated `/api/v1/**` under `mock-auth`.** Rejected — defeats the rip-out invariant ("the predicates stay"). The predicates need an authenticated principal to evaluate against.
- **Option D: `@WithMockUser` only.** Rejected — test-only annotation; doesn't help dev-runtime.
- **Option E (Signal Store): hand-roll an entity map in `withState`.** Rejected in favor of `@ngrx/signals/entities` `withEntities` — saves boilerplate, gives `setAllEntities` / `addEntity` / `updateEntity` operators, is the pattern S-049+ will copy. First `withEntities` consumer (deferred from S-006's TODO marker).
- **Option F (slug uniqueness): rely on DB unique index only, no service pre-check.** Rejected — pre-check produces a cleaner Bean-Validation-style error path for the common (non-race) case; DB index is still the source of truth.

### Proposed ADR amendments

- None for S-048 directly. The auth-chain stories (S-019 etc.) may amend ADR 0007 to mention the mock-auth seam as a documented dev-only path.

## Edge cases & hidden requirements

### Migration + table reality

- **Migration version is V5** (head is V4 — `V4__reservations_planning_accounting.sql`); not V6. New file: `V5__clubs_walking_skeleton.sql`.
- **Table is `club` (singular), already in V2.** `V2__identity_and_reference.sql:169` defines `club` with `clubname`, `club_key`, `country_id`, `club_state_id`, contact columns, `deleted_on`. V5 must `ALTER TABLE club ADD COLUMN ...` — NOT `CREATE TABLE`. Calling the table by the wrong name in DDL silently creates a separate relation.
- **`club_key` vs `slug` are different concepts.** `V2__identity_and_reference.sql:204` has `CREATE UNIQUE INDEX ux_club_key ON club (club_key)`. `slug` is new; V5 adds `ux_club_slug` separately. The service must distinguish the two 23505 error paths: duplicate `club_key` is data-integrity (not surfaced as form 409); duplicate `slug` maps to 409 the form must display.
- **`slug` null vs unique:** Postgres treats each `NULL` as distinct, so plain `CREATE UNIQUE INDEX` on a nullable column works. The migration uses partial form `WHERE slug IS NOT NULL` for clarity + future-proofing against PG version changes.
- **`public_registration_enabled` default:** must be `BOOLEAN NOT NULL DEFAULT false`. Omitting the default fails `ALTER TABLE` on a non-empty table because the new NOT NULL column has no value for existing rows.
- **Soft-delete `deleted_on` already in `club`** (`V2:199`). The list endpoint MUST filter `WHERE deleted_on IS NULL` or it surfaces tombstones. `ClubRepository` default scope excludes soft-deleted rows — via `@Where` annotation or explicit JPQL.

### Build classpath gaps

- **`spring-boot-starter-security` is NOT in `build.gradle.kts`.** Currently `next/server/build.gradle.kts:59-91` has no security starter. This story MUST add it. Side-effect: it auto-activates Spring Security's default chain which 401s every request — `MockSecurityConfig` replaces this default chain under the `mock-auth` profile.
- **`spring-boot-starter-data-jpa` is NOT in `build.gradle.kts`.** Currently `spring-boot-starter-jdbc` is on the classpath; this story adds the JPA starter (brings Hibernate). `spring.jpa.open-in-view` is already pinned to `false` in `application.yml:28` — good.
- **`@WebMvcTest` slice interaction:** once security is on the classpath, `HelloControllerIT` (currently no `@WithMockUser`) will return 401. Mitigation: either annotate existing tests with `@WithMockUser` OR ensure `MockSecurityConfig` is excluded from the slice (it is — `@Profile("mock-auth")` doesn't trigger under default test profile, so the slice has no chain; need to add a minimal `@TestConfiguration` for slice tests that permits all).
- **`OpenApiSnapshotIT` will break** unless `/v3/api-docs`, `/swagger-ui/**`, `/actuator/health` are permitted in `MockSecurityConfig`'s chain. Already addressed in the design (see permit-list above).

### Frontend wiring traps

- **`SessionStore.login()` MUST be called on bootstrap.** `authGuard` returns `false` (defer) while `sessionStatus` is `'idle'`/`'loading'` and redirects to `/login` when `'unauthenticated'`. Without `mockAuthBootstrap` calling `SessionStore.login(MOCK_USER, 'club-1')`, `/clubs` redirects to `/login` (which doesn't exist) → broken navigation. The bootstrap is non-optional.
- **`SessionStore.User.roles` enum drift** — currently `'CLUB_ADMIN' | 'SYSTEM_ADMIN' | 'MEMBER'`. Backend `@PreAuthorize` uses `SYSTEM_ADMINISTRATOR` (the canonical Keycloak realm role per S-019 design). Boyscout-rename in this story; saves a mapper at S-021.
- **`/clubs` route is absent from `app.routes.ts`.** Add with `canActivate: [authGuard]` (not `publicAccess: true`) so the guard seam stays real.
- **`MockAuthInterceptor` provider isolation:** the interceptor must NOT bundle into `pnpm build:prod`. Decide between (a) build-flag-gated `app.config.ts` overlay or (b) a `mockAuthInterceptor` that's a no-op without an env flag. Either way, CI greps `dist/` for `mock-sysadmin` and fails on hit.
- **`af-form-field` label association is manual.** Per S-008's convention, the consumer must match `<af-form-field [for]="X">` with the projected input's `id="X"`. Each Clubs form field MUST set both.
- **`af-data-table` tracks by item identity (`track item`).** After save, the API returns new object instances → all rows re-render. Acceptable for ≤100 rows; document as a known-trivial inefficiency that S-049 (first big list) may address with `track item.id`.

### CRUD shape decisions

- **`country_id` + `club_state_id` are NOT NULL FKs.** The walking-skeleton form must either (a) hard-code a seed UUID (e.g. canonical "CH" country UUID from V2 seed data) server-side and omit the field from the form, or (b) add a freetext placeholder. Pick (a) — explicit seed-value scope reduction; S-047 adds the picker later. Document in the controller Javadoc.
- **`clubKey` not updatable post-create.** Preserves the legacy identity-bearing invariant. PUT requests don't include `clubKey` in the DTO.
- **DTO field set is explicit (Java records).** No reflection-based binders. Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` blocks over-posting.
- **No `@Version` column.** Concurrent edits silently last-write-win — acceptable for walking-skeleton; S-067-style story addresses if needed.
- **Slug normalization (`toSlug` helper):** the `ClubsService` accepts the user-typed slug as-is (DTO `@Pattern` already enforces lowercase-kebab). A separate `toSlug(input: String)` helper that normalizes arbitrary input is **not** needed for S-048 (form already validates). If S-051+ wants auto-generated slugs from `clubname`, that's a separate concern.
- **Slug uniqueness race:** two concurrent POSTs with the same slug → one wins, the other gets a `DataIntegrityViolationException` from the UNIQUE index. Controller maps to 409. The form surfaces 409 as `slug: { duplicate: true }`.

### OpenAPI + generated-client drift

- **`OpenApiSnapshotIT` (S-003) compares live spec to committed snapshot.** Adding `ClubsController` changes the live spec. `./gradlew generateOpenApiSnapshot` must be re-run + the refreshed `next/openapi/openapi.json` (or `.yaml`) committed in the same PR. The CI `generate-api` step (S-004, `ci.yml:119-127`) diffs `src/app/api/generated/` against the committed snapshot — if the snapshot is stale, CI fails.
- **`generateOpenApiSnapshot` requires a live DataSource** (Spring boot loads JPA). Operator must have Docker (Postgres) up to regenerate. Document in the PR checklist.
- **API path shape:** `/api/v1/clubs` is plural per ADR 0005 convention. `/api/v1/clubs/{id}` for single-resource paths. orval generates `ClubsService.listClubs()`, `getClub(id)`, etc.

### Mock-auth rip-out audit

Exact files deleted in the single rip-out commit when S-019/S-020/S-022 land:
- `next/server/src/main/java/ch/alpenflight/auth/MockSecurityConfig.java` (whole file)
- `next/server/src/main/java/ch/alpenflight/auth/MockAuthenticationFilter.java` (whole file)
- `next/server/src/main/java/ch/alpenflight/auth/MockPrincipalClaims.java` (whole file)
- `next/server/src/main/java/ch/alpenflight/auth/package-info.java` (whole file)
- `next/server/src/main/resources/application-mock-auth.yml` (whole file)
- `next/web/src/app/core/auth/mock-auth.interceptor.ts` (whole file)
- `next/web/src/app/core/auth/mock-auth.bootstrap.ts` (whole file)
- `next/web/src/app/core/auth/README.md` (whole file)
- Lines in `app.config.ts` that conditionally provide the interceptor + bootstrap
- Compose env: `SPRING_PROFILES_ACTIVE: "mock-auth"` line on the `next/server` service

The rip-out is ONE commit; `@PreAuthorize`, `ClubsStore`, `ClubsController`, migration V5, all forms, the `ClubAwareJwtAuthenticationConverter` STAY untouched.

### Misc

- **CI strategy:** the existing `next-build` CI job doesn't currently boot Postgres + Spring + run Playwright against a live backend. A new `next-e2e` job (or extension of `next-build`) is needed for the walking-skeleton e2e — see Test plan Risks.
- **MUTATION_BUS event-kind extension:** adding `'club.created' | 'club.updated' | 'club.deleted'` to the discriminated union in `core/mutation-bus/mutation-bus.ts` is a typed-extension; consumers can opt in.
- **ESLint `no-restricted-imports` for feature stores:** the rule pattern `'../*/!(index)'` may or may not block `inject(SessionStore)` from `clubs.store.ts`. SessionStore is `providedIn: 'root'` core infra, not a sibling feature store. Verify the import path goes through `@core/session` (or barrel) and the lint passes; if not, add a clarifying exception or adjust the pattern.
- **`af-select` is part of the S-008 kit** but the walking-skeleton form doesn't need it (no FK pickers exposed). Future stories (S-047+) will exercise it against real reference data.
- **No `af-icon` / `af-badge` consumption needed** — those are JIT-deferred per S-008's design. Don't pre-build them in S-048.

## Security plan

The defining security concern of S-048 is **deliberate mocking of the auth chain** — a vertical slice that wires real `@PreAuthorize` predicates against a hard-coded `SYSTEM_ADMINISTRATOR` principal. Every mitigation below either (a) keeps that mock from leaking into production, or (b) keeps the rip-out clean when S-019 / S-020 / S-022 / S-026 land.

### Threat model

| Risk | Severity | Mitigation in S-048 |
|---|---|---|
| `mock-auth` profile accidentally activates in production | **Critical** | (a) `application-prod.properties` (or the prod profile that lands with S-040) contains an assertion / fail-fast bean that throws on startup if `mock-auth` is in `spring.profiles.active`. (b) `MockSecurityConfig` annotated `@Profile("mock-auth")` — bean not registered without the profile. (c) CI guard: grep `application*.properties` shipped on the prod-shaped path for `mock-auth` and fail the build if found. (d) `MockSecurityConfig` `@PostConstruct` logs a loud `WARN` banner "DEV-ONLY MOCK AUTH ACTIVE — DO NOT RUN IN PROD" to stdout so any operator sees it. |
| SPA `MockAuthInterceptor` ships in the production bundle | **Critical** | (a) Interceptor lives at `next/web/src/app/core/auth/mock-auth.interceptor.ts` and is registered ONLY in a dev-only `app.config.ts` overlay (or behind a `MOCK_AUTH` build-time flag that is `false` for `pnpm build:prod`). (b) CI guard: post-`build:prod` step greps `dist/` for the literal `mock-sysadmin`; non-zero hit count fails the build. (c) ESLint rule (preferred): ban `MockAuthInterceptor` import from any file outside `next/web/src/app/core/auth/`. |
| Rip-out path drifts — pieces of the mock layer survive after S-019/S-020 land | **High** | (a) Rip-out documented in `MockSecurityConfig` Javadoc: exact files + line ranges to delete. (b) S-019 and S-020 acceptance criteria include "delete the mock-auth layer" bullet. (c) Mock-profile-gated integration tests fail to compile after rip-out — that is the signal to delete them. (d) `MockAuthInterceptor` carries a `TODO-S-019` marker. |
| Mock principal shape doesn't match the real JWT, so `@PreAuthorize` works under mock but breaks under real auth | **High** | The mock `JwtAuthenticationToken` carries the SAME claim shape S-020's `JwtAuthenticationConverter` will produce: `sub`, `clubId` (user attribute), `realm_access.roles[]` mapped to `ROLE_*` `GrantedAuthority`s. Integration test under `mock-auth` asserts authorities contain `ROLE_SYSTEM_ADMINISTRATOR`. The principal accessor for `clubId` is on a shared `AuthenticatedPrincipal` interface — same interface backs both mock and real impl. |
| Roles spoofed via client-side state | **High** | Server-side `@PreAuthorize` is authoritative. SPA's `SessionStore` (advisory) controls UI element visibility (hide-button-if-not-sysadmin), but NEVER gates a write. Every mutation hits the server; server enforces. |
| `slug` injection (SQL / XSS via path or body) | **Medium** | Server: `@Pattern(regexp = "^[a-z0-9-]+$")` + `@Size(max = 64)` on DTO. JPA prepared statements. Frontend: Angular template interpolation auto-escapes; never `[innerHTML]`. URL path encoded by Angular Router/HttpClient. |
| DTO over-posting / mass assignment | **Medium** | DTOs are explicit Java `record` types — controller binds the record, NEVER the JPA entity. Service mapper copies field-by-field. No `BeanUtils.copyProperties`. |
| Unauthenticated requests reach `/api/v1/clubs/**` | **High** | The `mock-auth` `SecurityFilterChain` pre-authenticates EVERY request to the mock principal. Without `mock-auth`, no real chain is wired yet (S-020) — Spring Security's default chain rejects everything. Safe default holds. |
| Audit-log gap — mutations not logged | **Medium** | Out of scope; S-027 wires audit retroactively via AOP. Documented so the gap is intentional. |
| CORS / preflight | **Medium** | Same-origin assumption per `CLAUDE.md`; dev SPA proxies `/api/*` to backend. `WebMvcConfigurer` allows the dev origin only (`http://localhost:4200`); no wildcard. |
| CSRF | **N/A** | Bearer-token auth (mock or real) with no cookie session — CSRF doesn't apply. Spring Security CSRF disabled for `/api/**` is acceptable; documented in `MockSecurityConfig`. |
| Information disclosure via error responses | **Medium** | `@RestControllerAdvice` returns RFC 7807 `application/problem+json` with `code`, `title`, `detail`. No stack traces in prod (`server.error.include-stacktrace=never`). |
| Unique-slug race (two concurrent creates) | **Low** | DB-level `UNIQUE INDEX ux_club_slug ON club(slug) WHERE slug IS NOT NULL` is source of truth; service catches `DataIntegrityViolationException` → 409. |
| Enumeration via 404 vs 403 | **Low** | For `GET /api/v1/clubs/{id}`: when a `CLUB_ADMINISTRATOR` requests another club's ID, return 403 (not 404) — the existence is not the secret; the role gate is. Documented. |
| Dev-only mock token reused across environments | **Low** | The literal `mock-sysadmin` is a sentinel, not a secret. Anyone hitting a `mock-auth`-enabled backend is already in dev. Critical-row guards prevent the profile from reaching prod. |

### Authorization

- `GET /api/v1/clubs` — `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`. Tenant gate: N/A (Clubs ARE the tenant).
- `POST /api/v1/clubs` — `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`.
- `DELETE /api/v1/clubs/{id}` — `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`.
- `GET /api/v1/clubs/{id}` — `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') or (hasRole('CLUB_ADMINISTRATOR') and #id == principal.clubId)")`.
- `PUT /api/v1/clubs/{id}` — same as GET.
- UI route: `data: { requiredRole: 'SYSTEM_ADMINISTRATOR' }` consumed by S-006 route guard (advisory). Server-side `@PreAuthorize` is the actual gate.
- **Rip-out invariant:** at S-019/S-020 land, `@PreAuthorize` expressions and the principal-accessor interface DO NOT change. Only the principal source flips from `MockSecurityConfig` to JWT decoder + `JwtAuthenticationConverter`.

### Input validation

- DTO `ClubCreateRequest` / `ClubUpdateRequest` (records):
  - `name`: `@NotBlank @Size(max = 100)`
  - `slug`: `@NotBlank @Size(max = 64) @Pattern(regexp = "^[a-z0-9-]+$")`; aggregate re-validates per ADR 0022 directive 2 — schema does NOT carry CHECK.
  - `publicRegistrationEnabled`: primitive `boolean` (no null).
- Path variable `{id}`: typed as `UUID` — Spring rejects malformed with 400.
- Server returns RFC 7807 with `errors[]` field-level entries on 400; frontend `<af-field-errors>` renders via i18n keys.
- Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` blocks over-posting.

### PII handling

- `Club` columns (`id`, `name`, `slug`, `public_registration_enabled`) are NOT PII — public org-level identifiers. Logging policy: free to log at INFO.
- No PII at the Club entity level. (Person/Member data live elsewhere, S-051 territory.)
- Audit redaction: N/A at the Club level — when S-027 wires audit, `before`/`after` snapshots capture full Club rows without redaction.

### Audit-log events

(N/A — audit logging deferred to S-027.) Acceptance criteria explicitly drop audit; S-027 wires it via AOP on `@RestController` methods without touching `ClubsController`.

### Cross-tenant leakage

- **N/A — Clubs are the tenant boundary, not a tenant-scoped entity.** No `@TenantId` filter applies; queries against `club` are intentionally unscoped (the tenant root itself).
- The mock principal's `clubId="club-1"` exists for parity with S-049 (Locations, which IS tenant-scoped) but is unused by Clubs CRUD beyond the `#id == principal.clubId` predicate on read/update for `CLUB_ADMINISTRATOR`s.
- S-024 cross-tenant CI test does not assert against `/api/v1/clubs/**` — `Club` excluded from the test's scoped-entity set.

### OWASP applicability

- **A01 Broken Access Control:** applies. `@PreAuthorize` authoritative; mock principal is sysadmin so dev sees full access. Real auth tightens.
- **A02 Cryptographic Failures:** applies (dev only). Mock token is plaintext sentinel; CI guards prevent prod leak.
- **A03 Injection:** JPA prepared statements; DTO `@Pattern`; no string concat.
- **A04 Insecure Design:** mock layer intentionally insecure-by-config; profile-gated + CI guards + rip-out plan in Javadoc.
- **A05 Security Misconfiguration:** PRIMARY risk surface. Mitigations in threat model rows 1, 2, 3, 8.
- **A06 Vulnerable/Outdated Components:** Spring Boot 4 + Spring Security 7 pinned via Gradle catalog.
- **A07 Identification & Authentication Failures:** mock principal shared dev-only; documented.
- **A08 Integrity Failures:** N/A.
- **A09 Logging & Monitoring:** partial — audit deferred to S-027; mock-auth startup WARN banner ensures visibility.
- **A10 SSRF:** N/A — no outbound calls.

### CI / pre-commit guards

- **Backend** — `MockSecurityConfigProfileTest`: boot without `mock-auth`; assert `MockSecurityConfig` bean NOT registered.
- **Backend** — `MockAuthAuthorizationTest`: boot with `mock-auth`, mutate principal to drop `SYSTEM_ADMINISTRATOR`, assert sysadmin-only endpoints return 403 (proves `@PreAuthorize` actually gates).
- **Backend** — `ProdProfileForbidsMockAuthTest`: boot prod profile with `mock-auth` co-active; assert startup throws.
- **Frontend** — `pnpm build:prod` post-step: `grep -r "mock-sysadmin" dist/` returns non-zero → fail.
- **Frontend** — ESLint rule: `MockAuthInterceptor` import outside `next/web/src/app/core/auth/` fails lint.
- **Repo** — `.github/workflows/ci.yml` grep step: any `application-prod*.properties` with `spring.profiles.active=mock-auth` fails.

## Test plan

### Test pyramid for this story
- Unit: 10 — `ClubsService` slug helpers + `ClubsStore` state machine (vitest, no DOM).
- Integration: 8 — Spring `@SpringBootTest` with `mock-auth` profile + `SharedPostgresContainer`; covers full HTTP lifecycle, `@PreAuthorize` gate, and profile-gating of `MockSecurityConfig`.
- E2E: 5 — Playwright against real backend (`mock-auth` + compose `next` profile); new `next/web/e2e/tests/clubs/clubs-crud.spec.ts`.
- Parity: ported from legacy `e2e/tests/masterdata/clubs-crud.spec.ts` (CRUD-shape only; auth-protected assertions excluded — see Parity strategy).

### Unit tests

**Backend — JUnit 5, plain (no Spring context):**
- `slug_dtoPatternRejectsUppercase`: DTO `@Pattern` rejects `"My-Club"`; passes `"my-club"` — `ClubCreateRequest` validator.
- `slug_dtoPatternRejectsSpecialChars`: rejects `/`, `.`, `@`; passes hyphenated alphanumerics.
- `slug_rangeBoundaries`: rejects length 2 + length 65; accepts 3 + 64.
- `clubRename_trimsAndRejectsBlank`: `Club.rename(" ")` throws; `rename(" valid ")` strips whitespace.
- `slugUniquenessPreCheck_returnsConflict`: stub `ClubsRepository.existsBySlug("taken")` true → `ClubsService.create` throws `SlugAlreadyExistsException`.

**Frontend — Vitest, `next/web/`, logic only:**
- `ClubsStore_loadAll_happyPath`: stubs `ClubsService.listClubs()` returning two clubs; asserts `entities().length === 2`, `isLoading() === false`.
- `ClubsStore_loadAll_setsLoadError`: stubs 500 → `loadError()` non-null, `isLoading() === false`.
- `ClubsStore_create_optimisticPrependAndRevert`: stubs `createClub()` to error; asserts items list reverts.
- `ClubsStore_update_optimisticPatchAndRevert`: stubs `updateClub()` to error; asserts original restored + `saveError` set.
- `ClubsStore_clearsOnSessionLogout`: fires bus event; asserts `entities()` empty (mirrors `hello.store.spec.ts`).

### Integration tests

All `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "spring.profiles.active=mock-auth,test")`, `SharedPostgresContainer` + `@DynamicPropertySource`, annotated `@EnabledIf(...)`. New class `ClubsControllerIT`:

- `listClubs_mockSysadmin_returns200WithSeededClub`: GET `/api/v1/clubs`; mock principal sysadmin; HTTP 200 + response array contains seeded club.
- `createClub_validPayload_returns201WithLocation`: POST `{ name: "Test", slug: "test-club", clubKey: "TST" }`; HTTP 201 + `Location` header.
- `createClub_blankName_returns400`: POST with blank `name`; HTTP 400.
- `createClub_duplicateSlug_returns409`: seed one club, POST same slug; HTTP 409.
- `updateClub_existingId_returns200WithUpdatedPayload`: PUT changed name; HTTP 200 + new body.
- `updateClub_nonExistentId_returns404`: PUT zero-UUID; HTTP 404.
- `deleteClub_existingId_returns204`: DELETE; HTTP 204.
- `listClubs_excludesSoftDeleted`: insert row with `deleted_on` set; GET returns empty (or doesn't include it).

**`@PreAuthorize` smoke — `ClubsAuthorizationIT` (separate class):**
- `preAuthorize_clubAdminPrincipal_returns403OnList`: same boot with `mock-auth` but a separate `MockClubAdminSecurityConfig` (or `@WithMockUser(roles = "CLUB_ADMINISTRATOR")`); GET `/api/v1/clubs` → HTTP 403. **Load-bearing test** for "mock is real auth shape, not a bypass."

**Profile-gating — `MockSecurityConfigAbsenceIT`:**
- `mockSecurityConfig_notPresentWithoutMockAuthProfile`: boot with `spring.profiles.active=test` (no `mock-auth`); `ApplicationContext` does not contain `mockSecurityConfig` bean (mirrors `OpenApiOffByDefaultIT` pattern).

**Query-count assertion — `ClubsRepositoryPerfIT` (or fold into `ClubsControllerIT`):**
- `listClubs_emitsOneSelect`: seed 10 clubs; call list endpoint; assert Hibernate `Statistics.getQueryExecutionCount() == 1`.

### E2E tests

New file `next/web/e2e/tests/clubs/clubs-crud.spec.ts`. Backend running via `docker compose --profile next up` with `mock-auth`. No `page.route` mocking — all tests exercise real backend.

- `clubs_happyPath_editAndSave`: goto `/clubs`, see seeded row, click edit, change name, save, see update reflected.
- `clubs_createPath_newClubAppearsInList`: navigate to new-club form, fill name + unique-stamped slug + clubKey, submit, assert new row in list.
- `clubs_duplicateSlug_409SurfacedAsFormError`: submit existing slug, assert form-level "already in use" error.
- `clubs_keyboardOnly_tabAndEnterSubmit`: Tab through list + edit form, Enter on submit.
- `clubs_a11y_listAndEditPage`: axe-core scan on both pages; zero WCAG AA violations. Absorbs deferred S-008 a11y suite.

### Parity tests

Ported from `e2e/tests/masterdata/clubs-crud.spec.ts`. The legacy spec exercises full role-matrix assertions (SystemAdministrator vs ClubAdministrator vs others). Under mock-auth, only the SystemAdministrator-path assertions are meaningful. The ported spec asserts **observable CRUD behavior** (list, create, edit, delete, validation, 409 on duplicate slug, soft-delete filtered out) and **explicitly skips** the role-matrix paths until S-019/S-020 land real role-switching.

New spec file: `next/web/e2e/tests/clubs/clubs-crud.parity.spec.ts` (separate from the happy-path `clubs-crud.spec.ts` so the parity scope is reviewable in isolation).

- `parity_list_sysadminSeesAllClubs`: ported — `GET /api/v1/clubs` returns the seeded club + any created during the test. Legacy used login as SystemAdministrator + GET `/api/clubs`; new uses the mock principal which is sysadmin-equivalent.
- `parity_create_validClubPersistsAndAppearsInList`: ported — create → list, asserts presence.
- `parity_edit_updatesPersist`: ported — open edit form, change `name`, save, re-fetch, assert new value (mirrors legacy `cy.get(...).type(...).click()` pattern).
- `parity_delete_softDeletedClubNotInList`: ported — delete a club, assert subsequent list does NOT include it (legacy used `deletedOn` filter).
- `parity_duplicateSlug_returns409AndFormError`: ported — legacy validation path; the new system surfaces 409 as a field-level error on the slug control.

**Excluded from parity port** (covered in story frontmatter `parity_excluded:`):
- `legacy_clubAdmin_canOnlyEditOwnClub` — requires role-switching; deferred to S-019/S-020.
- `legacy_nonSysadmin_cannotList` — requires role-switching; deferred.
- `legacy_loginRequired_blocksUnauthenticated` — auth-flow specific; OIDC re-port at S-021.

The `ClubsAuthorizationIT` integration test (under `mock-auth` with downgraded principal) covers the `@PreAuthorize` predicate shape so the role-gating IS still exercised — just at the integration layer, not the Playwright layer.

### Test data + fixtures

- `clubs_seed_flyway`: V5 OR a separate `V<n>__seed_dev_clubs.sql` inserts at least one canonical row (`id = <canonical UUID>`, `slug = "seed-club-1"`, `clubKey = "SEED"`) for stable starting point. **Decision pinned in design notes:** seed in V5 with a fixed UUID literal so both backend ITs + Playwright reference the same row.
- `MockSysadminPrincipal` Java helper: static factory builds `Authentication` with `sub = "mock-sysadmin"`, `clubId = "club-1"`, `roles = ["SYSTEM_ADMINISTRATOR"]` matching the claim shape S-020 will produce. Inline in first test class; promote to `server.testsupport` when a second consumer arrives.
- `clubs_playwright_uniqueSlug` helper: inline `Date.now()`-stamped slug generator. No shared fixture — per-test isolation.

### Coverage gaps (deferred)

- Real OIDC enforcement → S-019 + S-020 + S-021.
- `@TenantId` enforcement → S-049 (clubs not scoped; first scoped consumer is S-049).
- Audit log → S-027.
- Bulk import → S-028.
- Public registration flow behavior → S-134.
- Concurrent last-write-win without `@Version` → out of scope; document.
- Playwright e2e in CI against live backend → see Risks (new CI job needed).

### Risks

- **Mock principal claim shape drift from real JWT:** if `MockSecurityConfig` builds a wrong-shape token, `@PreAuthorize` passes under mock but breaks under S-020. Mitigation: `MockSysadminPrincipal` fixture explicitly asserts `sub` + `clubId` + `realm_access.roles[]` structure in Javadoc; converter unit test asserts mapping when S-020 lands.
- **Playwright e2e CI infra gap:** `ci.yml` `next-build` job doesn't spin up the compose `next` profile. A new `next-e2e` job must start Spring + Postgres, wait for `/actuator/health` readiness, then run `pnpm e2e`. Add a `changes` filter (`next/**`) so PRs touching only docs skip it. **If this CI job doesn't land in S-048, document the gap and verify e2e manually before merge.**
- **`SharedPostgresContainer` context-cache collision:** `ClubsControllerIT` vs `MockSecurityConfigAbsenceIT` activate different profiles → two `ApplicationContext` instances → Flyway migrates twice. Flyway is idempotent, but V5 DDL written as `ADD COLUMN IF NOT EXISTS` (Postgres 9.6+) keeps things safe — or accept the second-boot reuses the same DB state.
- **Axe-core timing flakiness:** `injectAxe` + `checkA11y` after navigation can fail if rendering hasn't settled. Mitigation: wait for `[data-testid="clubs-table"]` visible before `checkA11y`.
- **Slug uniqueness race:** documented in Security plan; Postgres unique index is the gate.

### Parity strategy

`parity_test: e2e/tests/masterdata/clubs-crud.spec.ts` with two excluded categories (frontmatter `parity_excluded:` lists them verbatim):

1. **Role-matrix assertions** — legacy spec logs in as SystemAdministrator vs ClubAdministrator vs others to verify each role's access boundary. Under mock-auth the principal is fixed sysadmin; the role-gating discipline IS exercised via `ClubsAuthorizationIT` (integration test with a downgraded mock principal), so the security invariant has a passing test — just at a different layer.
2. **Login/logout flow** — legacy uses the `/Token` password grant; OIDC replaces this at S-021. Re-port lands then.

What IS ported: observable CRUD behavior (list, create, edit, delete, validation, 409 on duplicate slug, soft-delete exclusion). These map cleanly across the auth seam. When S-019/S-020 land, the role-matrix + login paths get layered back in as additional Playwright specs without touching the CRUD-shape specs here.

Tests asserting observable behavior — never legacy URL shape (legacy was `/api/clubs`, new is `/api/v1/clubs`) or response envelope. Parity is on what the user sees, not what the wire format is.

## Performance plan

### Hot paths

- **GET /api/v1/clubs (list)** — every Clubs page load + visibility-refetch. Full list, no pagination (≤100 rows even at SaaS scale). Hot in dev/demo, lukewarm in prod.
- **GET /api/v1/clubs/{id}** — edit-form open. PK lookup.
- **POST / PUT / DELETE /api/v1/clubs** — one-shot per user action.
- **Initial bundle for `/clubs` route** — Angular lazy chunk on first nav.
- **Mock-auth filter** — runs every request in dev. Intentionally zero-cost (in-memory principal injection, no JWT decode).

### Required indexes

- `clubs(id)` — PK, exists from V1 baseline.
- `club(slug)` — **new UNIQUE partial index** in V5. Source-of-truth uniqueness + supports future `findBySlug` lookup (S-025 public flows).
- `club(public_registration_enabled)` — **no index.** Low-cardinality boolean, no query filters by it independently.

### N+1 risks

- **No relations fetched at this scope.** `Club` is standalone in S-048 — no `@OneToMany`/`@ManyToOne` to Locations, Persons, Aircraft. List + read endpoints are single-statement.
- **Flag for S-049/S-051:** when those tables arrive with `@TenantId` on `clubId`, the Clubs list page should NOT trigger per-club queries for derived counts. Use projection + `GROUP BY` query.
- **Assertion baked into integration test:** `listClubs_emitsOneSelect` (see Test plan) verifies the no-N+1 contract before any related entity is added.

### Caching strategy

- **Server-side L2:** NOT enabled. Only Hibernate L1 (per-session) — sufficient for sub-ms small-table fetches.
- **Server-side HTTP:** no `Cache-Control` / `ETag` for the walking-skeleton. SPA store handles freshness. Revisit at S-108 if list becomes hot on mobile.
- **SPA (Signal Store):** `ClubsStore` is `providedIn: 'root'` singleton. Masterdata-like — adopt "cache long, TTL ~1h" bucket from S-006's refetch convention:
  - First `loadAll`: populate state + record `loadedAt`.
  - Subsequent route navs to `/clubs`: skip refetch if `Date.now() - loadedAt < 3_600_000`.
  - `document.visibilitychange` returning to `visible` + TTL exceeded: refetch.
  - Mutations: optimistic local + background reconcile.
- **Generated client (orval):** stateless. Caching layer is the Signal Store.

### Latency budget

| Path | Target p95 | Anchor |
| --- | --- | --- |
| GET /api/v1/clubs — server | < 100 ms | Small-table scan + JSON; NFR API p95 < 200 ms allows headroom |
| GET /api/v1/clubs/{id} — server | < 50 ms | PK lookup |
| POST /api/v1/clubs — server | < 100 ms | INSERT + UNIQUE check |
| PUT /api/v1/clubs/{id} — server | < 100 ms | UPDATE + optional UNIQUE re-check on slug |
| DELETE /api/v1/clubs/{id} — server | < 50 ms | DELETE by PK |
| GET /api/v1/clubs — SPA end-to-end | < 200 ms | Loopback dev network; meets NFR |
| `/clubs` cold route nav | < 800 ms | Mid-range laptop, warm backend; mobile-3G deferred to S-108 |
| Form save round-trip | < 300 ms | POST/PUT + optimistic store + nav back |
| Mock-auth filter overhead | < 1 ms | In-memory principal injection |

All targets are dev-environment, warm-backend. Mobile-3G / Vision §F12 validation deferred to S-108.

### Memory considerations

- Backend: `Club` ~6-10 scalar fields; 100-row list serializes to ~30 KB JSON. No streaming, no concern.
- SPA heap: `ClubsStore` 100 clubs × ~200 bytes ≈ ~20 KB heap. Trivial.
- `/clubs` lazy chunk: Signal Store + edit form + list page + DTO types ≈ ~30-50 KB gzipped. Verify lazy split (no eager import from shell).
- Initial bundle (raw) budget: post-S-008 5 MB gate stands. S-048 should NOT move the eager-shell bundle; all Clubs code in lazy chunk. > 100 KB eager regression → investigate.

### Performance test plan

- **Query-count assertion (CI hard gate):** Hibernate `Statistics.getQueryExecutionCount()` wrapped in test helper. List with N=10 → exactly 1 SELECT. Read by ID → 1 SELECT. POST → 1 INSERT. Asserts in `ClubsControllerIT` (or extracted `ClubsRepositoryPerfIT`).
- **Backend micro-benchmark (manual smoke):** 100 sequential `curl http://localhost:25568/api/v1/clubs | time` against `mock-auth`. Assert p95 < 100 ms. Document measured numbers in `## Review`.
- **Frontend logic test (vitest):** `ClubsStore.loadAll` with stubbed `getClubs()`. Synchronous state transitions under zoneless + signals. TTL-skip behavior asserted (second call within 1h is no-op; after TTL refetches).
- **Playwright timing soft-assertion:** record `performance.now()` from `page.goto('/clubs')` to table visible. Soft-assert < 1500 ms. Document; not a hard gate.
- **Bundle-size baseline (manual):** `pnpm build` post-S-048 — record eager initial-bundle + `/clubs` lazy chunk raw sizes in `## Review`. S-108 owns the proper CI gate.
- **No load test in this story.** rps / p99 / sustained → S-108.

### Risks

- **Hibernate L1 staleness on update → re-list:** each REST request opens a fresh transaction/session by default; verify no session-spanning constructs. Document in `ClubsService`.
- **Slug uniqueness race:** documented in Security; index is gate.
- **Cold Spring Boot start (dev):** ~5-15s first request after `docker compose up`. Playwright runner waits for `/actuator/health` `UP` before navigating.
- **SPA lazy-route regression:** stray eager `import` of `clubs.routes` from shell would pull entire Clubs feature into initial bundle. Post-build: verify `/clubs` lazy chunk exists as separate file under `dist/`, eager bundle doesn't contain `ClubsStore`.
- **CI Playwright job wall time:** new compose-backend + e2e step adds ~2-3 min. Gate behind `paths`-changes filter.
- **Mock-auth latency is artificially zero:** real S-020 adds ~5-10 ms per request for JWT signature verification (JWKS cached). All targets have ≥ 90 ms headroom vs NFR p95 < 200 ms — survives auth swap. Re-baseline at S-020.

### Out of scope (deferred)

- Production performance baseline → S-108.
- Bundle-size CI gate (raw + transfer) → S-108.
- Mobile-3G / Vision §F12 measurement → S-108.
- p99 sustained load → S-108.
- Lighthouse / Core Web Vitals → S-108.
- Service-worker / PWA caching → S-117.
- Real JWT decode latency → S-020.
- HTTP `Cache-Control` / `ETag` → S-108 (re-evaluate after real mobile traffic).
- Hibernate L2 cache → never, unless real hot-path emerges.

## Open design questions

- **Q1 — `next/server` compose service may not exist yet.** S-039 added Postgres + pgAdmin + Keycloak + CI; the backend service may not be in `docker-compose.yml` yet. If absent, S-048 either (a) adds it, or (b) the AC "via compose `next` profile" relaxes to "via `./gradlew bootRun --args='--spring.profiles.active=mock-auth'`" until the compose service lands. Operator decides at implement-phase start.

<!-- modernize-refine: end -->

## Review

<!-- modernize-review: start -->

### Parity
**Oracle:** `e2e/tests/masterdata/clubs-crud.spec.ts` (legacy live-backend club-admin round-trip) + `ClubsControllerIT` (testcontainer Postgres under `mock-auth`). Two-pass review: 6 first-pass blockers + 2 second-pass blockers all fixed in PR #57; remaining findings were improvements accepted as walking-skeleton / forward-looking deferrals.

<!-- modernize-review: end -->
