import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { pairwise, startWith } from 'rxjs';

import {
  needsTowplane,
  type FlightForm,
  type GliderFlightForm,
  type TowFlightForm,
} from './flight-form.model';

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
  clubDefaults(): {
    homebaseLocationId: string | null;
    defaultTowFlightTypeId: string | null;
    resetEngineOperatingCounters: boolean;
  };
}

const NO_EMIT = { emitEvent: false } as const;

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

    startTypeId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((next) => this.onStartTypeChange(next));

    glider.controls.flightTypeId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onGliderFlightTypeChange(id));

    glider.controls.flightCostBalanceTypeId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onFlightCostBalanceChange(id));

    glider.controls.aircraftId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onGliderAircraftChange(id));

    tow.controls.aircraftId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onTowAircraftChange(id));

    glider.controls.startLocationId.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((id) => this.onGliderStartLocationChange(id));

    glider.controls.noStartTimeInformation.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((flag) => this.onNoStartTimeInfoToggle(flag, glider, tow));

    glider.controls.ldgTime.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((t) => this.onLdgTimeFirstSet(t, glider));

    tow.controls.ldgTime.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((t) => this.onLdgTimeFirstSet(t, tow));

    glider.controls.isSoloFlight.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((solo) => this.onSoloToggle(solo));

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

  private onStartTypeChange(next: string | null): void {
    this.form.controls.glider.updateValueAndValidity();

    if (needsTowplane(next)) {
      const tow = this.form.controls.tow;
      const club = this.md.clubDefaults();
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
    if (this.md.clubDefaults().resetEngineOperatingCounters) {
      glider.controls.engineStartOperatingCounterInSeconds.setValue(null, NO_EMIT);
      glider.controls.engineEndOperatingCounterInSeconds.setValue(null, NO_EMIT);
    }
  }

  private onTowAircraftChange(id: string | null): void {
    if (!id) return;
    const tow = this.form.controls.tow;
    const club = this.md.clubDefaults();
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
    glider.controls.ldgLocationId.setValue(id, NO_EMIT);
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
