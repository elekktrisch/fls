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
import { TranslocoDirective } from '@jsverse/transloco';

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
import { resolveFlightVariant, variantNeedsTow, type FlightVariantData } from '../flight-variant';

import {
  CONFLICT_FIELD_TO_GLIDER_CONTROL,
  timeOfConflictValue,
  type ConflictFieldName,
} from './conflict-resolver';
import {
  FlightConflictPromptComponent,
  type ConflictResolution,
} from './flight-conflict-prompt.component';

import {
  buildDefaultsForCopy,
  buildDefaultsForEdit,
  buildDefaultsForNew,
} from './flight-form.defaults';
import { FlightFormCoordinator, type CoordinatorMetadata } from './flight-form.coordinator';
import { buildFlightForm, type FlightForm, type FlightFormSnapshot } from './flight-form.model';
import { FlightPrefsService } from './flight-prefs.service';
import { START_TYPE_OPTIONS } from './flight-start-types';

type Mode = 'new' | 'edit' | 'copy';

interface StepDescriptor {
  index: number;
  label: string;
  subtitle: string;
  conditional: boolean;
}

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
    FlightConflictPromptComponent,
    TranslocoDirective,
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
            data-testid="flight-submit-header"
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
          <nav class="grid gap-2 sm:grid-cols-3" data-testid="flight-stepper">
            @for (s of stepLabels(); track s.index) {
              <button
                type="button"
                class="flex min-h-16 items-start gap-3 border border-slate-200 px-4 py-3 text-left
                       hover:border-brand-300 focus:outline-hidden focus:ring-2 focus:ring-brand-400"
                [class.border-b-2]="step() === s.index"
                [class.border-b-brand-500]="step() === s.index"
                [class.bg-white]="step() === s.index"
                [class.bg-slate-50]="step() !== s.index"
                [attr.data-testid]="'flight-step-' + s.index"
                [attr.aria-current]="step() === s.index ? 'step' : null"
                (click)="goToStep(s.index)"
              >
                <span
                  class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center border text-xs font-medium"
                  [class.border-brand-500]="step() === s.index"
                  [class.bg-brand-500]="step() < s.index ? false : step() > s.index"
                  [class.text-white]="step() > s.index"
                  [class.text-brand-600]="step() === s.index"
                  [class.border-slate-300]="step() < s.index"
                  [class.text-slate-500]="step() < s.index"
                  aria-hidden="true"
                >
                  @if (step() > s.index) {
                    ✓
                  } @else {
                    {{ s.index + 1 }}
                  }
                </span>
                <span class="flex flex-col">
                  <span class="text-xs uppercase tracking-wide text-slate-500"
                    >Step {{ s.index + 1 }}</span
                  >
                  <span class="text-sm font-medium text-slate-900">{{ s.label }}</span>
                  <span class="text-xs text-slate-500">{{ s.subtitle }}</span>
                </span>
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
                <label
                  class="flex items-center gap-2 text-sm text-slate-700 cursor-pointer"
                  data-testid="flight-edit-glider-isSoloFlight-label"
                >
                  <input
                    type="checkbox"
                    class="w-4 h-4 accent-brand-500"
                    formControlName="isSoloFlight"
                    data-testid="flight-edit-glider-isSoloFlight"
                  />
                  Solo flight (no co-pilot)
                </label>
                @if (!gliderIsSolo()) {
                  <af-form-field label="Co-pilot">
                    <af-select
                      formControlName="coPilotPersonId"
                      [options]="personOptions()"
                      data-testid="flight-edit-glider-coPilot"
                    />
                  </af-form-field>
                }
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
                >Step {{ step() + 1 }} / {{ stepLabels().length }}</span
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
                    data-testid="flight-submit-sticky"
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
          <!--
            409 = state/policy conflict (time-gate reject, DeliveryBooked edit,
            optimistic-lock race). Non-blocking toast with a reload action —
            NEVER the inline diff (that's the 412 data-conflict path below).
          -->
          @if (reloadConflict()) {
            <div
              *transloco="let t; read: 'flight.reload'"
              class="flex items-center justify-between gap-3 border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-800"
              data-testid="flight-409-toast"
            >
              <span>{{ t('message') }}</span>
              <af-button data-testid="flight-409-reload" (clicked)="onReloadLatest()">{{
                t('action')
              }}</af-button>
            </div>
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

      <!-- 412 = data conflict → inline per-field keep-mine/keep-theirs diff. -->
      <af-flight-conflict-prompt
        [conflict]="conflict()"
        (resolved)="onConflictResolved($event)"
        (dismissed)="onConflictDismissed()"
      />
    </af-page>
  `,
})
export class FlightsEditPage {
  private readonly allSteps: readonly StepDescriptor[] = [
    { index: 0, label: 'Launch', subtitle: 'date · airfield · method', conditional: false },
    { index: 1, label: 'Glider', subtitle: 'aircraft · crew · times', conditional: false },
    { index: 2, label: 'Tow plane', subtitle: 'aircraft · pilot · landing', conditional: true },
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

  // 412 data conflict (drives the inline per-field diff dialog).
  protected readonly conflict = computed(() => this.store.saveConflict());
  // 409 state/policy conflict (drives the non-blocking reload toast).
  protected readonly reloadConflict = computed(() => this.store.hasReloadConflict());

  protected readonly title = computed(() => {
    const noun = this.variant().id === 'motor' ? 'air movement' : 'flight';
    switch (this.mode()) {
      case 'new':
        return `New ${noun}`;
      case 'copy':
        return `Copy ${noun}`;
      case 'edit':
      default:
        return `Edit ${noun}`;
    }
  });

  // `/flights` (default) vs `/airmovements` (motor) — same wizard, the route
  // `data.flightVariant` carries the difference (S-064).
  protected readonly variant = computed(() =>
    resolveFlightVariant(this.route.snapshot.data as FlightVariantData),
  );

  // Live signal of startTypeId so needsTow() responds to form changes.
  private readonly startTypeSignal = signal<string | null>(null);
  // Motor air movements never tow (variant suppresses the step regardless of
  // start-type); the default logbook defers to the aerotow start-type.
  protected readonly needsTow = computed(() =>
    variantNeedsTow(this.variant(), this.startTypeSignal()),
  );

  // Live solo flag so the co-pilot selector hides reactively and any
  // already-picked co-pilot is cleared the moment the flag flips on —
  // the UI invariant the form contract relies on (operator decision on
  // S-067 refine: persist as-given, never derive from crew).
  protected readonly gliderIsSolo = signal<boolean>(false);

  // The visible step set drops conditional steps (Tow) when their predicate
  // is false. Reactive — flips back on as soon as the user picks Aerotow.
  protected readonly stepLabels = computed<readonly StepDescriptor[]>(() => {
    const needs = this.needsTow();
    return this.allSteps.filter((s) => !s.conditional || needs);
  });

  protected readonly isLastStep = computed(() => this.step() === this.stepLabels().length - 1);

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

    // Track isSoloFlight: when it flips on, hide + clear the co-pilot
    // slot so the submitted snapshot can never contain a contradictory
    // (isSoloFlight=true, coPilotPersonId=non-null) state.
    this.form.controls.glider.controls.isSoloFlight.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((solo) => {
        this.gliderIsSolo.set(!!solo);
        if (solo) {
          this.form.controls.glider.controls.coPilotPersonId.setValue(null, {
            emitEvent: false,
          });
        }
      });

    // Clamp the active step when the visible step set shrinks (e.g. user
    // is on the Tow step and switches to Winch — the Tow step disappears
    // from the stepper, so the active step lands on the new last step).
    effect(() => {
      const max = this.stepLabels().length - 1;
      if (this.step() > max) {
        untracked(() => this.step.set(max));
      }
    });

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
    if (n < 0 || n >= this.stepLabels().length) return;
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
      await this.router.navigateByUrl(this.variant().basePath);
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
    void this.router.navigateByUrl(this.variant().basePath);
  }

  protected confirmDiscard(): void {
    this.dirtyConfirmOpen.set(false);
    this.form.markAsPristine();
    void this.router.navigateByUrl(this.variant().basePath);
  }

  /**
   * 412 resolved: apply each "keep theirs" choice onto the glider form, then
   * EXPLICITLY resubmit. The store refreshed `currentVersion` to the server's
   * version on the 412, so the resubmit If-Matches the current value. There is
   * NO auto-retry — this only runs because the user clicked resubmit.
   */
  protected onConflictResolved(resolution: ConflictResolution): void {
    const c = this.store.saveConflict();
    if (!c) {
      return;
    }
    const glider = this.form.controls.glider.controls as Record<
      string,
      { setValue: (v: unknown) => void }
    >;
    for (const field of c.fields) {
      if (resolution[field.name as ConflictFieldName] !== 'theirs') {
        continue; // "keep mine" → leave the user's value in the form.
      }
      const control = CONFLICT_FIELD_TO_GLIDER_CONTROL[field.name as ConflictFieldName];
      if (!control) {
        continue;
      }
      const value =
        field.name === 'startDateTime' || field.name === 'ldgDateTime'
          ? timeOfConflictValue(field.theirs)
          : field.theirs;
      glider[control]?.setValue(value);
    }
    this.store.dismissConflict();
    void this.finalSubmit();
  }

  protected onConflictDismissed(): void {
    this.store.dismissConflict();
  }

  /** 409 reload action: drop local state + re-hydrate from the server. */
  protected onReloadLatest(): void {
    this.store.dismissConflict();
    const id = this.store.current()?.id ?? this.route.snapshot.paramMap.get('id');
    if (id) {
      void this.hydrate('edit', id);
    }
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
    const variant = this.variant();
    return {
      flightId: raw.flightId,
      flightDate: raw.flightDate,
      startTypeId: raw.startTypeId,
      canUpdateRecord: raw.canUpdateRecord,
      canDeleteRecord: raw.canDeleteRecord,
      glider: { ...raw.glider, duration: null },
      tow: { ...raw.tow, duration: null },
      // /airmovements creates a MOTOR primary; the default logbook leaves this
      // unset (→ GLIDER). Edit reuses the existing row's type server-side, so
      // the override only bites on create.
      ...(variant.createAircraftType ? { primaryAircraftType: variant.createAircraftType } : {}),
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
    this.gliderIsSolo.set(snapshot.glider.isSoloFlight);
    this.form.markAsPristine();
  }

  private patchSub(
    group: FormGroup,
    snap: FlightFormSnapshot['glider'] | FlightFormSnapshot['tow'],
  ): void {
    group.patchValue(snap, { emitEvent: false });
  }
}
