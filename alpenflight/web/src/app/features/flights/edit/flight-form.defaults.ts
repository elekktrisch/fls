import type {
  FlightDetail,
  FlightLastContextResponse,
  FlightTemplateResponse,
} from '@api/generated/model';

import {
  flightDetailToFormSnapshot,
  templateToFormSnapshot,
  type CrewSnapshot,
  type FlightFormSnapshot,
} from './flight-form.model';
import type { FlightPrefs } from './flight-prefs.service';

/**
 * Resolution chain for the form snapshot. Per the refined design:
 *
 *   explicit Copy-from-Last > IndexedDB draft (S-062h) > copy-template
 *     > last-context > new-template > hardcoded fallback
 *
 * This story implements the chain minus the IndexedDB-draft tier and the
 * explicit Copy-from-Last per-field clicks (per-field buttons remain a UI
 * concern in the wizard). Drafts ship in S-062h. The pure-function shape
 * keeps the chain testable without TestBed.
 */

export function buildDefaultsForNew(
  template: FlightTemplateResponse,
  lastContext: FlightLastContextResponse | null,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  const base = templateToFormSnapshot(template);
  return applyLastContextThenPrefs(base, lastContext, prefs);
}

export function buildDefaultsForCopy(
  template: FlightTemplateResponse,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  const base = templateToFormSnapshot(template);
  // Copy template wins over last-context (it's a user-driven explicit pick).
  return applyPrefsOverlay(base, prefs);
}

export function buildDefaultsForEdit(
  glider: FlightDetail,
  tow: FlightDetail | undefined,
): FlightFormSnapshot {
  // Edit-load takes the server's truth verbatim; prefs / last-context do not
  // overlay an existing record.
  return flightDetailToFormSnapshot(glider, tow);
}

/**
 * Empty-field overlay precedence: `base ?? source ?? null` for every listed
 * `[snapshotKey, sourceKey]` pair (J-26 T-23). Each merge is "keep the base
 * value when set, else borrow the source's, else `null`" — the smart-default
 * rule (AC-DIR-5: never overwrite an explicit pick). Driving the 12 last-context
 * merges off these tables instead of 12 inline `??` triplets collapses the
 * function's branch count (fallow `applyLastContextThenPrefs` CRAP 210 hotspot).
 */
// The CrewSnapshot keys that hold a nullable id/route string (the only ones
// ever overlaid from context) — restricting the pair tables to these keeps the
// boolean/flag fields out of the merge so the clone-and-assign stays typed.
type CrewStringKey = {
  [K in keyof CrewSnapshot]: CrewSnapshot[K] extends string | null ? K : never;
}[keyof CrewSnapshot];

function coalesceCrew<S extends Record<string, unknown>>(
  base: CrewSnapshot,
  source: S,
  pairs: readonly (readonly [CrewStringKey, keyof S])[],
): CrewSnapshot {
  const merged: CrewSnapshot = { ...base };
  for (const [snapKey, sourceKey] of pairs) {
    merged[snapKey] = base[snapKey] ?? (source[sourceKey] as string) ?? null;
  }
  return merged;
}

// Glider crew fields seeded from the flat last-context response (same names on
// both sides). `aircraftId` is deliberately absent — the glider aircraft is the
// user's explicit pick, never borrowed from context.
const GLIDER_CTX_PAIRS = [
  ['flightTypeId', 'flightTypeId'],
  ['pilotPersonId', 'pilotPersonId'],
  ['invoiceRecipientPersonId', 'invoiceRecipientPersonId'],
  ['startLocationId', 'startLocationId'],
  ['ldgLocationId', 'ldgLocationId'],
  ['outboundRoute', 'outboundRoute'],
  ['inboundRoute', 'inboundRoute'],
  ['flightCostBalanceTypeId', 'flightCostBalanceTypeId'],
] as const satisfies readonly (readonly [CrewStringKey, keyof FlightLastContextResponse])[];

// Tow crew fields seeded from `ctx.tow` (TowContext) — only when present.
const TOW_CTX_PAIRS = [
  ['aircraftId', 'aircraftId'],
  ['flightTypeId', 'flightTypeId'],
  ['pilotPersonId', 'pilotPersonId'],
  ['ldgLocationId', 'ldgLocationId'],
] as const satisfies readonly (readonly [
  CrewStringKey,
  keyof NonNullable<FlightLastContextResponse['tow']>,
])[];

function applyLastContextThenPrefs(
  base: FlightFormSnapshot,
  ctx: FlightLastContextResponse | null,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  if (!ctx) return applyPrefsOverlay(base, prefs);
  const merged: FlightFormSnapshot = {
    ...base,
    startTypeId: base.startTypeId ?? ctx.startTypeId ?? null,
    glider: coalesceCrew(base.glider, ctx, GLIDER_CTX_PAIRS),
    tow: ctx.tow ? coalesceCrew(base.tow, ctx.tow, TOW_CTX_PAIRS) : base.tow,
  };
  return applyPrefsOverlay(merged, prefs);
}

function applyPrefsOverlay(base: FlightFormSnapshot, prefs: FlightPrefs): FlightFormSnapshot {
  // lastStartLocation auto-hydrates as the workstation default — overlays empty
  // fields only (per AC-DIR-5: smart defaults never overwrite explicit picks).
  const startLoc = prefs.lastStartLocation;
  if (!startLoc) return base;
  return {
    ...base,
    glider: {
      ...base.glider,
      startLocationId: base.glider.startLocationId ?? startLoc,
      ldgLocationId: base.glider.ldgLocationId ?? startLoc,
    },
    tow: {
      ...base.tow,
      startLocationId: base.tow.startLocationId ?? startLoc,
      ldgLocationId: base.tow.ldgLocationId ?? startLoc,
    },
  };
}
