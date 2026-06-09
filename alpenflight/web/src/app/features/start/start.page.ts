import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { AircraftStore } from '@features/aircraft/aircraft.store';
import { FlightTypesStore } from '@features/flight-types/flight-types.store';
import { LocationsStore } from '@features/locations/locations.store';

import { formatIsoDateDdMmYyyy } from '@shared/util/date';

import { AfPageComponent } from '@ui/molecules/af-page';

import { DEFAULT_LOCALE } from '../../core/i18n/lang-resolver';
import { SessionStore } from '../../core/session/session.store';

import { StartStore } from './start.store';

/**
 * Locale-aware date formatter via the browser's Intl. Angular's
 * {@code DatePipe} would require {@code registerLocaleData(localeDe)} +
 * the same for fr/it, which the project doesn't ship today; Intl works
 * for every modern browser locale without registration and without the
 * per-locale bundle cost.
 */
function formatLocaleDate(date: Date | null, locale: string, style: 'long' | 'medium'): string {
  if (!date) return '';
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: style }).format(date);
  } catch {
    return new Intl.DateTimeFormat(DEFAULT_LOCALE, { dateStyle: style }).format(date);
  }
}

// Full transloco paths so the i18n-key-coverage spec resolves them via
// static regex scan — that spec only matches literal strings inside the
// transloco directive call, so a dynamic-concat prefix would slip through.
type GreetingKey = 'greeting.morning' | 'greeting.afternoon' | 'greeting.evening';

function pickGreeting(hourOfDay: number): GreetingKey {
  if (hourOfDay < 12) return 'greeting.morning';
  if (hourOfDay < 18) return 'greeting.afternoon';
  return 'greeting.evening';
}

// FlightCrewType seed UUIDs per V3 — canonical source is
// `ch.alpenflight.flights.domain.FlightCrewTypeIds` on the server. Keep
// the map in lockstep; a seed re-id would surface here as "—" until this
// map is patched (or as a wrong label, which the e2e spec's `PIC`
// assertion catches for the populated path).
// Values are full transloco paths (not bare leaf names) so the
// i18n-key-coverage spec's static-scan regex resolves them — that spec only
// matches literal strings inside the directive call, so a dynamic-concat
// prefix would slip through. Same reason greetingKey returns a full path.
const CREW_ROLE_LABEL: Record<string, string> = {
  '019e2e15-2c00-76b0-8000-0000000036b0': 'lastFlight.roles.pic', // PILOT_OR_STUDENT
  '019e2e15-2c00-76b1-8000-0000000036b1': 'lastFlight.roles.coPilot',
  '019e2e15-2c00-76b2-8000-0000000036b2': 'lastFlight.roles.instructor',
  '019e2e15-2c00-76b3-8000-0000000036b3': 'lastFlight.roles.passenger',
  '019e2e15-2c00-76b4-8000-0000000036b4': 'lastFlight.roles.winchOperator',
  '019e2e15-2c00-76b5-8000-0000000036b5': 'lastFlight.roles.observer',
  '019e2e15-2c00-76b6-8000-0000000036b6': 'lastFlight.roles.flightCostInvoiceRecipient',
};

@Component({
  selector: 'af-start',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslocoDirective, AfPageComponent],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'home'">
        <header class="mb-8 space-y-1">
          <h1 class="text-2xl font-medium text-slate-900" data-testid="start-greeting">
            {{ t(greetingKey(), { name: displayName() }) }}
          </h1>
          <p class="text-slate-500" data-testid="start-today">{{ formattedToday() }}</p>
        </header>

        <!-- min-[900px] is the AC's exact breakpoint; matches legacy
             screens-home.jsx:249-261. Not a custom Tailwind token. -->
        <div class="grid grid-cols-1 gap-6 min-[900px]:grid-cols-2 mb-8">
          @if (store.showLastFlight()) {
            <a
              class="block border border-slate-200 p-5 hover:border-brand-500 cursor-pointer focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
              data-testid="start-last-flight-card"
              [routerLink]="['/flights', flightId(), 'edit']"
            >
              <header class="flex items-baseline justify-between gap-3 mb-3">
                <h2 class="text-lg font-medium text-slate-900">{{ t('lastFlight.title') }}</h2>
                <span class="text-sm tabular text-slate-500">{{ formattedLastFlightDate() }}</span>
              </header>
              <!-- max-content keeps the label column auto-sized so longer
                   localized labels (FR "Type de vol", IT "Tipo di volo")
                   don't squeeze the value column on narrow viewports. -->
              <dl class="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm text-slate-700">
                <dt class="text-slate-500">{{ t('lastFlight.aircraft') }}</dt>
                <dd class="tabular">{{ aircraftImmat() }}</dd>
                <dt class="text-slate-500">{{ t('lastFlight.route') }}</dt>
                <dd>{{ routeLabel() }}</dd>
                <dt class="text-slate-500">{{ t('lastFlight.flightType') }}</dt>
                <dd>{{ flightTypeLabel() }}</dd>
                <dt class="text-slate-500">{{ t('lastFlight.role') }}</dt>
                <dd data-testid="start-last-flight-role">{{ myRoleLabel(t) }}</dd>
              </dl>
            </a>
          } @else if (store.showEmptyState()) {
            <div
              class="border border-slate-200 p-5 space-y-3"
              data-testid="start-last-flight-empty"
            >
              <h2 class="text-lg font-medium text-slate-900">{{ t('lastFlight.title') }}</h2>
              <p class="text-slate-600">{{ t('lastFlight.empty.message') }}</p>
              <a
                class="inline-flex items-center justify-center px-4 py-2 min-h-[44px] bg-brand-500 text-white hover:bg-brand-600 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
                data-testid="start-empty-cta"
                [routerLink]="['/flights', 'new']"
              >
                {{ t('lastFlight.empty.cta') }}
              </a>
            </div>
          } @else if (store.hasError()) {
            <div
              class="border border-slate-200 p-5 space-y-2"
              data-testid="start-last-flight-error"
            >
              <h2 class="text-lg font-medium text-slate-900">{{ t('lastFlight.title') }}</h2>
              <p class="text-red-600">{{ t('lastFlight.error') }}</p>
            </div>
          }
          <!-- Pre-attempt / first-paint: render no card at all (per ADR 0024
               "spinner only after 300ms" — a single-line title card with no
               body content reads as a layout glitch). The reservations
               placeholder keeps the row's grid shape stable. -->

          <div
            class="border border-slate-200 p-5 space-y-2"
            data-testid="start-reservation-placeholder"
          >
            <h2 class="text-lg font-medium text-slate-900">{{ t('reservations.title') }}</h2>
            <p class="text-slate-500">{{ t('reservations.placeholder') }}</p>
          </div>
        </div>

        <nav class="flex flex-wrap gap-3">
          <a
            class="inline-flex items-center justify-center px-4 py-2 min-h-[44px] border border-slate-300 text-slate-800 hover:border-slate-500 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
            data-testid="start-quick-open-logbook"
            [routerLink]="['/flights']"
          >
            {{ t('quickActions.openLogbook') }}
          </a>
          <a
            class="inline-flex items-center justify-center px-4 py-2 min-h-[44px] bg-brand-500 text-white hover:bg-brand-600 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
            data-testid="start-quick-log-flight"
            [routerLink]="['/flights', 'new']"
          >
            {{ t('quickActions.logFlight') }}
          </a>
        </nav>
      </ng-container>
    </af-page>
  `,
})
export class StartPage {
  private readonly session = inject(SessionStore);
  protected readonly store = inject(StartStore);
  private readonly aircraft = inject(AircraftStore);
  private readonly locations = inject(LocationsStore);
  private readonly flightTypes = inject(FlightTypesStore);
  private readonly transloco = inject(TranslocoService);

  protected readonly today = computed(() => new Date());
  protected readonly locale = computed(() => this.transloco.getActiveLang() || DEFAULT_LOCALE);
  protected readonly formattedToday = computed(() =>
    formatLocaleDate(this.today(), this.locale(), 'long'),
  );
  // DD.MM.YYYY date-only (J-6b T-12 — legacy hardcodes it). `formatIsoDateDdMmYyyy`
  // formats the `YYYY-MM-DD` string directly, sidestepping the
  // `new Date('YYYY-MM-DD')` UTC-midnight gotcha (a CH flight on 2026-05-21
  // mustn't render as 2026-05-20 west of UTC).
  protected readonly formattedLastFlightDate = computed(() =>
    formatIsoDateDdMmYyyy(this.store.lastFlight()?.flightDate),
  );

  protected readonly displayName = computed(() => {
    const user = this.session.authenticatedUser();
    if (!user) return '';
    return user.firstName?.trim() || user.username || '';
  });

  protected readonly greetingKey = computed<GreetingKey>(() =>
    pickGreeting(this.today().getHours()),
  );

  protected readonly flightId = computed(() => this.store.lastFlight()?.id ?? null);

  protected readonly aircraftImmat = computed(() => {
    const f = this.store.lastFlight();
    if (!f) return '—';
    return this.aircraft.entityMap()[f.aircraftId]?.immatriculation ?? '—';
  });

  protected readonly routeLabel = computed(() => {
    const f = this.store.lastFlight();
    if (!f) return '—';
    const start = f.startLocationId
      ? (this.locations.entityMap()[f.startLocationId]?.icaoCode ??
        this.locations.entityMap()[f.startLocationId]?.locationName ??
        '—')
      : '—';
    const ldg = f.ldgLocationId
      ? (this.locations.entityMap()[f.ldgLocationId]?.icaoCode ??
        this.locations.entityMap()[f.ldgLocationId]?.locationName ??
        '—')
      : '—';
    return `${start} → ${ldg}`;
  });

  protected readonly flightTypeLabel = computed(() => {
    const f = this.store.lastFlight();
    if (!f?.flightTypeId) return '—';
    return this.flightTypes.entityMap()[f.flightTypeId]?.flightTypeName ?? '—';
  });

  protected myRoleLabel(t: (key: string) => string): string {
    const me = this.session.authenticatedUser()?.personId;
    const f = this.store.lastFlight();
    if (!me || !f?.crew) return '—';
    const myRow = f.crew.find((c) => c.personId === me);
    if (!myRow) return '—';
    const key = CREW_ROLE_LABEL[myRow.flightCrewTypeId];
    return key ? t(key) : '—';
  }

  constructor() {
    effect(() => {
      const personId = this.session.authenticatedUser()?.personId ?? null;
      if (personId) {
        this.store.load(personId);
      } else if (this.session.isAuthenticated()) {
        // /me resolved but the user has no linked Person — render the empty
        // state directly per the AC (no flights round-trip, no warn log).
        this.store.markNoPersonLink();
      }
    });
  }
}
