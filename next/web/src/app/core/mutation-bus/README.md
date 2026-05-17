# MutationBus

Application-wide event bus for cross-store cache invalidation.

## Why

Domain signal stores are silos. When a mutation in one domain invalidates
cached data in another (logout, tenant switch, aircraft update), the source
store fires a typed event on `MUTATION_BUS` and every consumer reacts in its
own `withHooks(onInit)`.

**Domain stores never inject sibling stores.** Coupling stores by direct
injection collapses their lifecycles, defeats per-store testing, and grows a
quadratic dependency graph as features land. `MUTATION_BUS` is the single
fan-out seam.

## Event shape

```ts
type MutationEvent =
  | { kind: 'session.logout' }
  | { kind: 'session.tenantSwitch'; clubId: string }
  | { kind: 'aircraft.created'; aircraftId: string }
  | …
```

Conventions:

1. **Name format:** `<domain>.<past-tense-verb>` — `aircraft.created`,
   `flight.booked`, `session.logout`. Reads naturally in `switch` arms.
2. **IDs only, never bodies.** No `entity`, no `user`, no `payload`, no
   `token`. Subscribers fetch by ID if they need the new value. The
   TypeScript discriminated union refuses any field outside the
   declared shape — adding a new event without an ID-only payload is a
   compile error.
3. **Not an audit channel.** Audit log lives on the server (S-027). The
   bus is *client-side cache fan-out*, nothing else.

## Consumer pattern

```ts
export const AircraftsStore = signalStore(
  { providedIn: 'root' },
  withState(initial),
  withHooks({
    onInit(store, bus = inject(MUTATION_BUS)) {
      bus.pipe(takeUntilDestroyed()).subscribe((evt) => {
        switch (evt.kind) {
          case 'session.logout':
          case 'session.tenantSwitch':
            patchState(store, initial);
            break;
          case 'aircraft.updated':
            store.reload(evt.aircraftId);
            break;
        }
      });
    },
  }),
);
```

Every domain store MUST clear on `session.logout` and `session.tenantSwitch`
to avoid cross-tenant signal leak. See `next/web/src/app/auth/README.md`
for the full lifecycle.

## Lint backing

`eslint.config.mjs` bans cross-feature store imports from
`features/**/*.store.ts`. The rule is structural; this doc is the
review-time discipline that fills the gap (use the bus, not a direct
injection).
