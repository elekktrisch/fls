# S-048 mock-auth seam (DELETE in S-019/S-020 land commit)

Walking-skeleton authentication shim. The whole folder ships an explicit rip-out checklist so the auth chain swap is mechanical rather than spelunking.

## What's here

- `mock-auth.interceptor.ts` — stamps `Authorization: Bearer mock-sysadmin` on every `/api/v1/**` HttpClient request so the backend's `MockAuthenticationFilter` sees a Bearer token shape and Spring Security can resolve a principal.
- `mock-auth.bootstrap.ts` — `provideAppInitializer` factory that runs `SessionStore.login(MOCK_USER, 'club-1')` before the first route navigation. Without it the `authGuard` redirects `/clubs` to `/login` (which doesn't exist).

## Rip-out checklist (one commit when S-019 + S-020 + S-022 land)

- [ ] Delete this directory (`src/app/core/auth/`).
- [ ] Remove the two providers from `app.config.ts` (the comments call them out).
- [ ] Drop `SPRING_PROFILES_ACTIVE=mock-auth` from any local / compose run config.
- [ ] On the backend, delete `ch.alpenflight.auth.*`, `application-mock-auth.yml`, and the mock IT classes (see `MockSecurityConfig` Javadoc for the full list).

`ClubAwareJwtAuthenticationConverter` stays — S-020 wires it into the real OAuth2 resource server chain.
