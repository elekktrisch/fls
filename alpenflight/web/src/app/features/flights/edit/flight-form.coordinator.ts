import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { pairwise, startWith } from 'rxjs';

import {
  needsTowplane,
  type FlightForm,
  type GliderFlightForm,
  type TowFlightForm,
} from './flight-form.model';

/**
 * Read-only metadata the wizard hands the coordinator. The wizard owns the
 * Signal Store lookups (S-006); the coordinator owns the rules. Pure-TS access
 * keeps the coordinator testable without Angular DI.
 */
export interface CoordinatorMetadata {
  aircraft(id: string | null | undefined): {
    nrOfSeats: number | null;
    hasEngine: boolean;
  } | null;
  flightType(id: string | null | undefined): {
    isSoloFlight: boolean;
    isPassengerFlight: boolean;
    instructorRequired: boolean;
    isFlightCostBalanceSelectable: boolean;
  } | null;
  flightCostBalanceType(id: string | null | undefined): {
    personForInvoiceRequired: boolean;
  } | null;
  location(id: string | null | undefined): {
    isOutboundRouteRequired: boolean;
    isInboundRouteRequired: boolean;
  } | null;
  /** Club defaults applied by `resetTowFlightDefaults` on tow aircraft pick. */
  clubDefaults(): {
    homebaseLocationId: string | null;
    defaultTowFlightTypeId: string | null;
    resetEngineOperatingCounters: boolean;
  };
}

const NO_EMIT = { emitEvent: false } as const;

/**
 * Plain TS class — no Angular DI. The wizard component instantiates this
 * once in ngOnInit and calls `attach(form, metadata, destroyRef)`.
 *
 * Owns the cross-field reactive rules from the parity oracle (see the story's
 * `## Client form mechanics` § Cross-field reactive rules). All `patchValue`
 * calls use `emitEvent: false` to avoid `valueChanges` re-entry deadlocks
 * (Vitest fakeAsync sentinel).
 */
export class FlightFormCoordinator {
  private form!: FlightForm;
  private md!: CoordinatorMetadata;

  attach(form: FlightForm, metadata: CoordinatorMetadata, destroyRef: DestroyRef): void {
    this.form = form;
    this.md = metadata;
    this.wireRules(destroyRef);
  }

  private wireRules(destroyRef: DestroyRef): void {
    const { glider, tow, startTypeId } = this.form.controls;

    // Rule: startType change — when changing AWAY from Towing, ldgTime mirrors
    // don't apply but tow values are kept in memory; the submit-time tow-discard
    // (see snapshotToCreateRequests) handles the partial-data drop.
    startTypeId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((next) => this.onStartTypeChange(next));

    // Rule: glider.flightTypeId change → recompute solo tri-state + clear conditionals.
    glider.controls.flightTypeId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onGliderFlightTypeChange(id));

    // Rule: glider.flightCostBalanceTypeId change → conditionally clear invoice recipient.
    glider.controls.flightCostBalanceTypeId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onFlightCostBalanceChange(id));

    // Rule: glider.aircraftId change — seat-count force-solo, engine-counter reset (conditional).
    glider.controls.aircraftId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onGliderAircraftChange(id));

    // Rule: tow.aircraftId change → resetTowFlightDefaults (fill empties from club defaults).
    tow.controls.aircraftId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onTowAircraftChange(id));

    // Rule: glider.startLocationId change → mirror to ldgLocationId + tow.startLocationId + tow.ldgLocationId.
    glider.controls.startLocationId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onGliderStartLocationChange(id));

    // Rule: glider.noStartTimeInformation toggle → propagate to tow (asymmetric — landing doesn't propagate).
    glider.controls.noStartTimeInformation.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((flag) => this.onNoStartTimeInfoToggle(flag, glider, tow));

    // Rule: glider.ldgTime blur → default nrOfLdgs=1 if unset.
    glider.controls.ldgTime.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((t) => this.onLdgTimeFirstSet(t, glider));

    // Rule: tow.ldgTime blur → default tow.nrOfLdgs=1 if unset (parity FlightsController.js:749).
    tow.controls.ldgTime.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((t) => this.onLdgTimeFirstSet(t, tow));

    // Rule: isSoloFlight toggle to true → clear coPilotPersonId.
    glider.controls.isSoloFlight.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((solo) => this.onSoloToggle(solo));

    // Rule: engine counters blur → mirror engineDurationSeconds (derived; not in form yet — leave for UI step).
    // Duration mirror (glider.startTime / ldgTime / duration) — see computeDurationFromTimes below.
    // Hooked up in the step components for now since the duration field is display-only there.

    // Mirror canUpdateRecord onto each sub-group's disabled state — the wizard
    // body disables when the server flag is false.
    this.form.controls.canUpdateRecord.valueChanges
      .pipe(
        startWith(this.form.controls.canUpdateRecord.value),
        pairwise(),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe(([prev, next]) => {
        if (prev === next) return;
        if (next) {
          this.form.enable(NO_EMIT);
        } else {
          this.form.disable(NO_EMIT);
        }
      });
  }

  // ============================== Rules ===============================

  private onStartTypeChange(next: string | null): void {
    // Tow sub-form is always built (cheap); the wizard's @if (needsTowplane(…))
    // template gate controls visibility. Nothing to do here today; the rule
    // exists so future logic (e.g. resetting tow.flightTypeId default on
    // first Towing select) has a hook.
    if (needsTowplane(next)) {
      const tow = this.form.controls.tow;
      const club = this.md.clubDefaults();
      // Seed defaults on the first transition INTO Towing, only when empty.
      if (!tow.controls.flightTypeId.value && club.defaultTowFlightTypeId) {
        tow.controls.flightTypeId.setValue(club.defaultTowFlightTypeId, NO_EMIT);
      }
    }
  }

  private onGliderFlightTypeChange(id: string | null): void {
    const ft = this.md.flightType(id);
    if (!ft) return;
    const glider = this.form.controls.glider;
    if (ft.isSoloFlight) {
      glider.controls.isSoloFlight.setValue(true, NO_EMIT);
      glider.controls.coPilotPersonId.setValue(null, NO_EMIT);
    } else if (ft.isPassengerFlight) {
      glider.controls.isSoloFlight.setValue(false, NO_EMIT);
      glider.controls.coPilotPersonId.setValue(null, NO_EMIT);
    }
  }

  private onFlightCostBalanceChange(id: string | null): void {
    const fcb = this.md.flightCostBalanceType(id);
    if (!fcb) return;
    if (!fcb.personForInvoiceRequired) {
      this.form.controls.glider.controls.invoiceRecipientPersonId.setValue(null, NO_EMIT);
    }
  }

  private onGliderAircraftChange(id: string | null): void {
    const a = this.md.aircraft(id);
    const glider = this.form.controls.glider;
    if (!a) return;
    if (a.nrOfSeats === 1 && !glider.controls.isSoloFlight.value) {
      glider.controls.isSoloFlight.setValue(true, NO_EMIT);
      glider.controls.coPilotPersonId.setValue(null, NO_EMIT);
    }
    // Engine-counter reset is conditional on club.resetEngineOperatingCounters
    // AND on a real aircraft change (not draft restore). The wizard owns the
    // draft-restore guard and calls `clearEngineCounters()` directly.
    if (this.md.clubDefaults().resetEngineOperatingCounters) {
      glider.controls.engineStartOperatingCounterInSeconds.setValue(null, NO_EMIT);
      glider.controls.engineEndOperatingCounterInSeconds.setValue(null, NO_EMIT);
    }
  }

  private onTowAircraftChange(id: string | null): void {
    if (!id) return;
    const tow = this.form.controls.tow;
    const club = this.md.clubDefaults();
    // resetTowFlightDefaults — fill empties only (don't overwrite user input).
    if (!tow.controls.startLocationId.value && club.homebaseLocationId) {
      tow.controls.startLocationId.setValue(club.homebaseLocationId, NO_EMIT);
    }
    if (!tow.controls.ldgLocationId.value && club.homebaseLocationId) {
      tow.controls.ldgLocationId.setValue(club.homebaseLocationId, NO_EMIT);
    }
    if (!tow.controls.flightTypeId.value && club.defaultTowFlightTypeId) {
      tow.controls.flightTypeId.setValue(club.defaultTowFlightTypeId, NO_EMIT);
    }
    if (tow.controls.nrOfLdgs.value == null) {
      tow.controls.nrOfLdgs.setValue(1, NO_EMIT);
    }
  }

  private onGliderStartLocationChange(id: string | null): void {
    const glider = this.form.controls.glider;
    const tow = this.form.controls.tow;
    // Overwrite ldgLocationId on the glider — legacy parity (FlightsController.js:649-656).
    glider.controls.ldgLocationId.setValue(id, NO_EMIT);
    // Mirror to tow start AND tow landing — those are display-only / disabled in the UI.
    tow.controls.startLocationId.setValue(id, NO_EMIT);
    tow.controls.ldgLocationId.setValue(id, NO_EMIT);
  }

  private onNoStartTimeInfoToggle(
    flag: boolean,
    glider: GliderFlightForm,
    tow: TowFlightForm,
  ): void {
    if (flag) {
      glider.controls.startTime.setValue(null, NO_EMIT);
    }
    // Propagates to tow (start only; landing flag is NOT mirrored — asymmetric by design).
    if (tow.controls.noStartTimeInformation.value !== flag) {
      tow.controls.noStartTimeInformation.setValue(flag, NO_EMIT);
    }
  }

  private onLdgTimeFirstSet(t: string | null, sub: GliderFlightForm | TowFlightForm): void {
    if (!t) return;
    if (sub.controls.nrOfLdgs.value == null) {
      sub.controls.nrOfLdgs.setValue(1, NO_EMIT);
    }
  }

  private onSoloToggle(solo: boolean): void {
    if (solo) {
      this.form.controls.glider.controls.coPilotPersonId.setValue(null, NO_EMIT);
    }
  }
}
