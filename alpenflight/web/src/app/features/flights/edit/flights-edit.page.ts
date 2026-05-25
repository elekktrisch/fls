import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  Injector,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AircraftStore } from '@features/aircraft/aircraft.store';
import { FlightTypesStore } from '@features/flight-types/flight-types.store';
import { LocationsStore } from '@features/locations/locations.store';
import { PersonsStore } from '@features/persons/persons.store';

import { SessionStore } from '@core/session/session.store';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfTimeNowButtonComponent } from '@ui/atoms/af-time-now-button';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfStickyBarComponent } from '@ui/molecules/af-sticky-bar';
import { AfDialogComponent } from '@ui/organisms/af-dialog';

import { FlightStore } from '../flight.store';

import {
  buildDefaultsForCopy,
  buildDefaultsForEdit,
  buildDefaultsForNew,
} from './flight-form.defaults';
import { FlightFormCoordinator, type CoordinatorMetadata } from './flight-form.coordinator';
import {
  buildFlightForm,
  needsTowplane,
  type FlightForm,
  type FlightFormSnapshot,
} from './flight-form.model';
import { FlightPrefsService } from './flight-prefs.service';
import { START_TYPE_OPTIONS } from './flight-start-types';

type Mode = 'new' | 'edit' | 'copy';

/**
 * 3-step wizard shell for create / edit / copy of a flight.
 *
 * - Step 1 (Launch): flightDate, startType, glider start location.
 * - Step 2 (Glider): aircraft, flight type, pilot + conditional crew, times,
 *   landings, route, comment.
 * - Step 3 (Tow): rendered only when `needsTowplane(startType)`; otherwise
 *   surfaced as an empty state.
 *
 * Save / paired-create orchestration lives in `FlightStore.savePair` /
 * `updatePair`; the wizard just gathers the snapshot.
 */
@Component({
  selector: 'af-flights-edit-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    AfButtonComponent,
    AfDialogComponent,
    AfFormFieldComponent,
    AfInputComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfSelectComponent,
    AfStickyBarComponent,
    AfTimeNowButtonComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="title()">
        <af-button data-testid="flight-cancel" (clicked)="onCancel()">Cancel</af-button>
        <!--
          Inline header save only on >=lg; the sticky-bar Save on the last
          step takes over on <lg (the next/back button area). Two distinct
          slots, not duplicated.
        -->
        <div class="hidden lg:contents">
          <af-button
            type="primary"
            data-testid="flight-submit"
            [disabled]="saving()"
            (clicked)="finalSubmit()"
            >Save</af-button
          >
        </div>
      </af-page-header>

      @if (loading()) {
        <p class="text-slate-600" data-testid="flight-loading">Loading...</p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="onEnter()" data-testid="flight-form" class="space-y-6">
          <nav class="flex gap-2 border-b border-slate-200 pb-2" data-testid="flight-stepper">
            @for (s of stepLabels; track s.index) {
              <button
                type="button"
                class="min-h-11 px-4 py-2 text-sm"
                [class.text-brand-600]="step() === s.index"
                [class.font-medium]="step() === s.index"
                [attr.data-testid]="'flight-step-' + s.index"
                (click)="goToStep(s.index)"
              >
                {{ s.index + 1 }}. {{ s.label }}
              </button>
            }
          </nav>

          @switch (step()) {
            @case (0) {
              <section class="space-y-4" data-testid="flight-step-launch">
                <af-form-field label="Flight date" [for]="'flight-edit-flightDate'">
                  <af-input
                    type="date"
                    inputId="flight-edit-flightDate"
                    formControlName="flightDate"
                    data-testid="flight-edit-flightDate"
                  />
                </af-form-field>
                <af-form-field label="Start type">
                  <af-select
                    formControlName="startTypeId"
                    [options]="startTypeOptions()"
                    data-testid="flight-edit-startType"
                  />
                </af-form-field>
                <ng-container formGroupName="glider">
                  <af-form-field label="Start location">
                    <af-select
                      formControlName="startLocationId"
                      [options]="locationOptions()"
                      data-testid="flight-edit-startLocation"
                    />
                  </af-form-field>
                </ng-container>
              </section>
            }
            @case (1) {
              <section formGroupName="glider" class="space-y-4" data-testid="flight-step-glider">
                <af-form-field label="Aircraft" [for]="'flight-edit-glider-aircraft'">
                  <af-select
                    formControlName="aircraftId"
                    [options]="gliderAircraftOptions()"
                    data-testid="flight-edit-glider-aircraft"
                  />
                </af-form-field>
                <af-form-field label="Flight type">
                  <af-select
                    formControlName="flightTypeId"
                    [options]="gliderFlightTypeOptions()"
                    data-testid="flight-edit-glider-flightType"
                  />
                </af-form-field>
                <af-form-field label="Pilot">
                  <af-select
                    formControlName="pilotPersonId"
                    [options]="personOptions()"
                    data-testid="flight-edit-glider-pilot"
                  />
                </af-form-field>
                <div class="grid grid-cols-2 gap-3">
                  <af-form-field label="Start time">
                    <div class="flex gap-2">
                      <af-input
                        type="time"
                        formControlName="startTime"
                        data-testid="flight-edit-glider-startTime"
                      />
                      <af-time-now-button (nowSet)="onGliderStartNow($event)" />
                    </div>
                  </af-form-field>
                  <af-form-field label="Landing time">
                    <af-input
                      type="time"
                      formControlName="ldgTime"
                      data-testid="flight-edit-glider-ldgTime"
                    />
                  </af-form-field>
                </div>
                <af-form-field label="Landings">
                  <af-input
                    type="number"
                    inputmode="numeric"
                    formControlName="nrOfLdgs"
                    data-testid="flight-edit-glider-nrOfLdgs"
                  />
                </af-form-field>
                <af-form-field label="Comment">
                  <af-input
                    formControlName="flightComment"
                    data-testid="flight-edit-glider-comment"
                  />
                </af-form-field>
              </section>
            }
            @case (2) {
              @if (needsTow()) {
                <section formGroupName="tow" class="space-y-4" data-testid="flight-step-tow">
                  <af-form-field label="Tow aircraft">
                    <af-select
                      formControlName="aircraftId"
                      [options]="towAircraftOptions()"
                      data-testid="flight-edit-tow-aircraft"
                    />
                  </af-form-field>
                  <af-form-field label="Tow pilot">
                    <af-select
                      formControlName="pilotPersonId"
                      [options]="personOptions()"
                      data-testid="flight-edit-tow-pilot"
                    />
                  </af-form-field>
                  <af-form-field label="Landing time">
                    <af-input
                      type="time"
                      formControlName="ldgTime"
                      data-testid="flight-edit-tow-ldgTime"
                    />
                  </af-form-field>
                </section>
              } @else {
                <p class="text-slate-600" data-testid="flight-step-tow-skipped">
                  No tow plane required for this start type.
                </p>
              }
            }
          }

          <af-sticky-bar>
            <div class="flex items-center justify-between">
              <af-button
                data-testid="flight-step-back"
                [disabled]="step() === 0"
                (clicked)="goToStep(step() - 1)"
                >Back</af-button
              >
              <span class="text-sm text-slate-500"
                >Step {{ step() + 1 }} / {{ stepLabels.length }}</span
              >
              @if (!isLastStep()) {
                <af-button
                  data-testid="flight-step-next"
                  type="primary"
                  (clicked)="goToStep(step() + 1)"
                  >Next</af-button
                >
              } @else {
                <!-- Sticky-bar save only on <lg; on >=lg the header action carries it. -->
                <div class="lg:hidden">
                  <af-button
                    data-testid="flight-submit"
                    type="primary"
                    [disabled]="saving()"
                    (clicked)="finalSubmit()"
                    >Save flight</af-button
                  >
                </div>
                <span class="hidden lg:inline"></span>
              }
            </div>
          </af-sticky-bar>

          @if (errorMessage()) {
            <p class="text-red-600" data-testid="flight-error">{{ errorMessage() }}</p>
          }
          @if (conflict()) {
            <p class="text-amber-600" data-testid="flight-conflict-toast">
              Concurrent edit detected — reload to keep working.
            </p>
          }
        </form>
      }

      <af-dialog
        [visible]="dirtyConfirmOpen()"
        title="Discard changes?"
        message="You have unsaved changes. Leave anyway?"
        confirmLabel="Discard"
        dismissLabel="Keep editing"
        (confirm)="confirmDiscard()"
        (dismiss)="dirtyConfirmOpen.set(false)"
      />
    </af-page>
  `,
})
export class FlightsEditPage {
  protected readonly stepLabels = [
    { index: 0, label: 'Launch' },
    { index: 1, label: 'Glider' },
    { index: 2, label: 'Tow' },
  ];

  private readonly fb: NonNullableFormBuilder = inject(FormBuilder).nonNullable;
  protected readonly form: FlightForm = buildFlightForm(this.fb);

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly store = inject(FlightStore);
  private readonly aircraftStore = inject(AircraftStore);
  private readonly personsStore = inject(PersonsStore);
  private readonly locationsStore = inject(LocationsStore);
  private readonly flightTypesStore = inject(FlightTypesStore);
  private readonly session = inject(SessionStore);
  private readonly prefs = inject(FlightPrefsService);
  private readonly coordinator = new FlightFormCoordinator();

  protected readonly mode = signal<Mode>('new');
  protected readonly step = signal<number>(0);
  protected readonly loading = signal<boolean>(false);
  protected readonly saving = signal<boolean>(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly dirtyConfirmOpen = signal<boolean>(false);

  protected readonly conflict = computed(() => this.store.hasSaveConflict());

  protected readonly title = computed(() => {
    switch (this.mode()) {
      case 'new':
        return 'New flight';
      case 'copy':
        return 'Copy flight';
      case 'edit':
      default:
        return 'Edit flight';
    }
  });

  protected readonly isLastStep = computed(() => this.step() === this.stepLabels.length - 1);

  // Live signal of startTypeId so needsTow() responds to form changes.
  private readonly startTypeSignal = signal<string | null>(null);
  protected readonly needsTow = computed(() => needsTowplane(this.startTypeSignal()));

  protected readonly locationOptions = computed<AfSelectOption<string>[]>(() =>
    this.locationsStore.entities().map((l) => ({ value: l.id, label: l.locationName })),
  );

  protected readonly personOptions = computed<AfSelectOption<string>[]>(() =>
    this.personsStore.entities().map((p) => ({
      value: p.id,
      label: `${p.firstname} ${p.lastname}`,
    })),
  );

  protected readonly gliderAircraftOptions = computed<AfSelectOption<string>[]>(() =>
    this.aircraftStore
      .entities()
      .filter((a) => !a.isTowingAircraft)
      .map((a) => ({ value: a.id, label: a.immatriculation })),
  );

  protected readonly towAircraftOptions = computed<AfSelectOption<string>[]>(() =>
    this.aircraftStore
      .entities()
      .filter((a) => a.isTowingAircraft)
      .map((a) => ({ value: a.id, label: a.immatriculation })),
  );

  protected readonly gliderFlightTypeOptions = computed<AfSelectOption<string>[]>(() =>
    this.flightTypesStore
      .entities()
      .filter((t) => t.isForGliderFlights)
      .map((t) => ({ value: t.id, label: t.flightTypeName })),
  );

  protected readonly startTypeOptions = computed<AfSelectOption<string>[]>(() =>
    START_TYPE_OPTIONS.map((s) => ({ value: s.id, label: s.label })),
  );

  private readonly metadata: CoordinatorMetadata = {
    aircraft: (id) => {
      if (!id) return null;
      const a = this.aircraftStore.entities().find((x) => x.id === id);
      if (!a) return null;
      // List-projection row doesn't carry nrOfSeats; the rule no-ops when
      // unknown. Detail-fetch when needed lives in S-062h's IDB-draft pass.
      return { nrOfSeats: null, hasEngine: a.hasEngine };
    },
    flightType: (id) => {
      if (!id) return null;
      const t = this.flightTypesStore.entities().find((x) => x.id === id);
      if (!t) return null;
      return {
        isSoloFlight: false,
        isPassengerFlight: false,
        instructorRequired: false,
        isFlightCostBalanceSelectable: t.isFlightCostBalanceSelectable,
      };
    },
    flightCostBalanceType: () => null,
    location: (id) => {
      if (!id) return null;
      const l = this.locationsStore.entities().find((x) => x.id === id);
      if (!l) return null;
      return { isOutboundRouteRequired: false, isInboundRouteRequired: false };
    },
    clubDefaults: () => ({
      homebaseLocationId: null,
      defaultTowFlightTypeId: null,
      resetEngineOperatingCounters: true,
    }),
  };

  constructor() {
    this.coordinator.attach(this.form, this.metadata, this.destroyRef);

    // Track startType for live needsTow gating + step skip.
    this.form.controls.startTypeId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((v) => this.startTypeSignal.set(v));

    // Resolve mode + id from the route; load the appropriate snapshot.
    effect(() => {
      const params = this.route.snapshot.paramMap;
      const url = this.route.snapshot.url.map((s) => s.path);
      const id = params.get('id');
      const head = url[0] ?? '';
      let mode: Mode = 'edit';
      if (head === 'new') mode = 'new';
      else if (head === 'copy') mode = 'copy';
      this.mode.set(mode);
      untracked(() => {
        void this.hydrate(mode, id);
      });
    });
  }

  protected goToStep(n: number): void {
    if (n < 0 || n >= this.stepLabels.length) return;
    this.step.set(n);
  }

  protected onGliderStartNow(time: string): void {
    this.form.controls.glider.controls.startTime.setValue(time);
  }

  /**
   * Enter-key handler — advances to the next step on intermediate steps,
   * submits only on the last step. Per AC-DIR-3a: "Enter advances step /
   * submits on last step".
   */
  protected onEnter(): void {
    if (this.isLastStep()) {
      void this.finalSubmit();
    } else {
      this.goToStep(this.step() + 1);
    }
  }

  protected async finalSubmit(): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.errorMessage.set(null);
    try {
      const snap = this.snapshot();
      const userSub = this.session.authenticatedUser()?.id ?? null;

      if (this.mode() === 'new' || this.mode() === 'copy') {
        await this.store.savePair(snap);
      } else {
        const versions = {
          glider: this.store.currentVersion() ?? 1,
          tow: this.store.currentTowVersion(),
        };
        await this.store.updatePair(snap, versions);
      }

      // Persist Copy-from-Last anchors for the next session.
      if (userSub && snap.glider.startLocationId) {
        await this.prefs.update(userSub, 'lastStartLocation', snap.glider.startLocationId);
      }
      if (userSub && snap.tow.aircraftId) {
        await this.prefs.update(userSub, 'lastTowAircraftId', snap.tow.aircraftId);
        if (snap.tow.pilotPersonId) {
          await this.prefs.recordTowPilot(userSub, snap.tow.aircraftId, snap.tow.pilotPersonId);
        }
      }

      this.form.markAsPristine();
      await this.router.navigateByUrl('/flights');
    } catch (e) {
      const err = e as { message?: string };
      this.errorMessage.set(err.message ?? 'Could not save flight');
    } finally {
      this.saving.set(false);
    }
  }

  protected onCancel(): void {
    if (this.form.dirty) {
      this.dirtyConfirmOpen.set(true);
      return;
    }
    void this.router.navigateByUrl('/flights');
  }

  protected confirmDiscard(): void {
    this.dirtyConfirmOpen.set(false);
    this.form.markAsPristine();
    void this.router.navigateByUrl('/flights');
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.dirtyConfirmOpen()) {
      // The dialog itself owns dismissal — let it close cleanly instead of
      // re-firing the cancel flow.
      this.dirtyConfirmOpen.set(false);
      return;
    }
    this.onCancel();
  }

  private snapshot(): FlightFormSnapshot {
    const raw = this.form.getRawValue();
    return {
      flightId: raw.flightId,
      flightDate: raw.flightDate,
      startTypeId: raw.startTypeId,
      canUpdateRecord: raw.canUpdateRecord,
      canDeleteRecord: raw.canDeleteRecord,
      glider: { ...raw.glider, duration: null },
      tow: { ...raw.tow, duration: null },
    } as FlightFormSnapshot;
  }

  private async hydrate(mode: Mode, id: string | null): Promise<void> {
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      const userSub = this.session.authenticatedUser()?.id ?? null;
      const prefs = userSub ? await this.prefs.get(userSub) : {};

      if (mode === 'new') {
        const template = await this.store.loadNewTemplate();
        const aircraftId = template.aircraftId;
        const lastCtx =
          aircraftId && template.flightDate
            ? await this.store.loadLastContext(aircraftId, template.flightDate)
            : null;
        this.patch(buildDefaultsForNew(template, lastCtx, prefs));
      } else if (mode === 'copy') {
        if (!id) throw new Error('copy: missing :id');
        const template = await this.store.loadCopyTemplate(id);
        this.patch(buildDefaultsForCopy(template, prefs));
      } else {
        if (!id) throw new Error('edit: missing :id');
        await this.store.loadDetail(id);
        const glider = this.store.current();
        if (!glider) throw new Error('flight not found');
        const tow = this.store.currentTow();
        this.patch(buildDefaultsForEdit(glider, tow ?? undefined));
      }
    } catch (e) {
      this.errorMessage.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  private patch(snapshot: FlightFormSnapshot): void {
    this.form.patchValue(
      {
        flightId: snapshot.flightId,
        flightDate: snapshot.flightDate,
        startTypeId: snapshot.startTypeId,
        canUpdateRecord: snapshot.canUpdateRecord,
        canDeleteRecord: snapshot.canDeleteRecord,
      },
      { emitEvent: false },
    );
    this.patchSub(this.form.controls.glider, snapshot.glider);
    this.patchSub(this.form.controls.tow, snapshot.tow);
    this.startTypeSignal.set(snapshot.startTypeId);
    this.form.markAsPristine();
  }

  private patchSub(
    group: FormGroup,
    snap: FlightFormSnapshot['glider'] | FlightFormSnapshot['tow'],
  ): void {
    group.patchValue(snap, { emitEvent: false });
  }
}
up.patchValue(snap, { emitEvent: false });
  }
}
