import type {
  FlightDetail,
  FlightLastContextResponse,
  FlightTemplateResponse,
} from '@api/generated/model';

import {
  flightDetailToFormSnapshot,
  templateToFormSnapshot,
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

function applyLastContextThenPrefs(
  base: FlightFormSnapshot,
  ctx: FlightLastContextResponse | null,
  prefs: FlightPrefs,
): FlightFormSnapshot {
  if (!ctx) return applyPrefsOverlay(base, prefs);
  const merged: FlightFormSnapshot = {
    ...base,
    startTypeId: base.startTypeId ?? ctx.startTypeId ?? null,
    glider: {
      ...base.glider,
      flightTypeId: base.glider.flightTypeId ?? ctx.flightTypeId ?? null,
      pilotPersonId: base.glider.pilotPersonId ?? ctx.pilotPersonId ?? null,
      invoiceRecipientPersonId:
        base.glider.invoiceRecipientPersonId ?? ctx.invoiceRecipientPersonId ?? null,
      startLocationId: base.glider.startLocationId ?? ctx.startLocationId ?? null,
      ldgLocationId: base.glider.ldgLocationId ?? ctx.ldgLocationId ?? null,
      outboundRoute: base.glider.outboundRoute ?? ctx.outboundRoute ?? null,
      inboundRoute: base.glider.inboundRoute ?? ctx.inboundRoute ?? null,
      flightCostBalanceTypeId:
        base.glider.flightCostBalanceTypeId ?? ctx.flightCostBalanceTypeId ?? null,
    },
    tow: ctx.tow
      ? {
          ...base.tow,
          aircraftId: base.tow.aircraftId ?? ctx.tow.aircraftId ?? null,
          flightTypeId: base.tow.flightTypeId ?? ctx.tow.flightTypeId ?? null,
          pilotPersonId: base.tow.pilotPersonId ?? ctx.tow.pilotPersonId ?? null,
          ldgLocationId: base.tow.ldgLocationId ?? ctx.tow.ldgLocationId ?? null,
        }
      : base.tow,
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
