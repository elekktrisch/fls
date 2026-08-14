import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { tapResponse } from '@ngrx/operators';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import {
  addEntity,
  removeEntity,
  setAllEntities,
  setEntity,
  withEntities,
} from '@ngrx/signals/entities';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { AccountingReferenceDataService } from '@api/generated/accounting-reference-data/accounting-reference-data.service';
import { AccountingRuleFiltersService } from '@api/generated/accounting-rule-filters/accounting-rule-filters.service';
import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { FlightTypesService } from '@api/generated/flight-types/flight-types.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { MemberStatesService } from '@api/generated/member-states/member-states.service';
import type {
  AccountingRuleFilterDetail,
  AccountingRuleFilterListItem,
  AccountingRuleFilterTypeResponse,
  AccountingRuleFilterWriteRequest,
  AccountingUnitTypeResponse,
  AircraftListItem,
  FlightCrewTypeResponse,
  FlightTypeListItem,
  LocationListItem,
  PersonListItem,
} from '@api/generated/model';
import { PersonsService } from '@api/generated/persons/persons.service';

import { classifyApiError, type SaveErrorRule } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type AccountingRuleFilterItem = AccountingRuleFilterListItem & { id: string };
export type AccountingRuleFilterDetailLoaded = AccountingRuleFilterDetail & { id: string };

export type SaveErrorKind = 'conflict' | 'forbidden' | 'not-found' | 'other';

export interface MatchListOptions {
  aircraftImmatriculations: readonly string[];
  flightTypeCodes: readonly string[];
  locations: readonly string[];
  clubMemberNumbers: readonly string[];
  flightCrewTypes: readonly string[];
}

const emptyMatchListOptions: MatchListOptions = {
  aircraftImmatriculations: [],
  flightTypeCodes: [],
  locations: [],
  clubMemberNumbers: [],
  flightCrewTypes: [],
};

interface AccountingExtraState {
  selectedId: string | null;
  selectedDetail: AccountingRuleFilterDetailLoaded | null;
  filterTypes: readonly AccountingRuleFilterTypeResponse[];
  accountingUnitTypes: readonly AccountingUnitTypeResponse[];
  matchListOptions: MatchListOptions;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: AccountingExtraState = {
  selectedId: null,
  selectedDetail: null,
  filterTypes: [],
  accountingUnitTypes: [],
  matchListOptions: emptyMatchListOptions,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

function withListItemId(f: AccountingRuleFilterListItem): AccountingRuleFilterItem {
  if (!f.id) {
    throw new Error('AccountingRuleFilterListItem without id — server contract violation');
  }
  return f as AccountingRuleFilterItem;
}

function withDetailId(d: AccountingRuleFilterDetail): AccountingRuleFilterDetailLoaded {
  if (!d.id) {
    throw new Error('AccountingRuleFilterDetail without id — server contract violation');
  }
  return d as AccountingRuleFilterDetailLoaded;
}

function listItemFromDetail(d: AccountingRuleFilterDetailLoaded): AccountingRuleFilterItem {
  return {
    id: d.id,
    ruleFilterName: d.ruleFilterName,
    filterTypeId: d.filterTypeId,
    active: d.active,
    sortIndicator: d.sortIndicator,
    target: '',
  };
}

export const AccountingStore = signalStore(
  { providedIn: 'root' },
  withEntities<AccountingRuleFilterItem>(),
  withState<AccountingExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail, filterTypes }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedFilter: computed(() => selectedDetail()),
    filterTypeNameById: computed(() => new Map(filterTypes().map((ty) => [ty.id, ty.name]))),
  })),
  withMethods(
    (
      store,
      accountingApi = inject(AccountingRuleFiltersService),
      referenceApi = inject(AccountingReferenceDataService),
      aircraftApi = inject(AircraftService),
      locationsApi = inject(LocationsService),
      flightTypesApi = inject(FlightTypesService),
      personsApi = inject(PersonsService),
      memberStatesApi = inject(MemberStatesService),
      bus = inject(MUTATION_BUS),
    ) => {
      const loadAll = rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isLoading: true, loadError: null })),
          switchMap(() =>
            accountingApi.listAccountingRuleFilters().pipe(
              tapResponse({
                next: (items: AccountingRuleFilterListItem[]) =>
                  patchState(store, setAllEntities(items.map(withListItemId)), {
                    isLoading: false,
                    lastRefreshedAt: Date.now(),
                  }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { loadError: e.message, isLoading: false }),
              }),
            ),
          ),
        ),
      );
      const loadFilterTypes = rxMethod<void>(
        pipe(
          switchMap(() =>
            referenceApi.listAccountingRuleFilterTypes().pipe(
              tapResponse({
                next: (types: AccountingRuleFilterTypeResponse[]) =>
                  patchState(store, { filterTypes: types }),
                error: () => patchState(store, { filterTypes: [] }),
              }),
            ),
          ),
        ),
      );
      const loadUnitTypes = rxMethod<void>(
        pipe(
          switchMap(() =>
            referenceApi.listAccountingUnitTypes().pipe(
              tapResponse({
                next: (types: AccountingUnitTypeResponse[]) =>
                  patchState(store, { accountingUnitTypes: types }),
                error: () => patchState(store, { accountingUnitTypes: [] }),
              }),
            ),
          ),
        ),
      );
      const patchOptions = (patch: Partial<MatchListOptions>): void =>
        patchState(store, (state) => ({
          matchListOptions: { ...state.matchListOptions, ...patch },
        }));
      const prefetchMemberStatesWithoutSuggestionTokens = (): void => {
        memberStatesApi.listMemberStates().subscribe({ error: () => undefined });
      };
      const loadMatchListReferences = rxMethod<void>(
        pipe(
          tap(() => {
            aircraftApi.listAircraft().subscribe({
              next: (rows: AircraftListItem[]) =>
                patchOptions({
                  aircraftImmatriculations: dedupeTokens(rows.map((a) => a.immatriculation)),
                }),
              error: () => patchOptions({ aircraftImmatriculations: [] }),
            });
            locationsApi.listLocations().subscribe({
              next: (rows: LocationListItem[]) =>
                patchOptions({ locations: dedupeTokens(rows.map((l) => l.icaoCode)) }),
              error: () => patchOptions({ locations: [] }),
            });
            flightTypesApi.listFlightTypes().subscribe({
              next: (rows: FlightTypeListItem[]) =>
                patchOptions({ flightTypeCodes: dedupeTokens(rows.map((f) => f.flightCode)) }),
              error: () => patchOptions({ flightTypeCodes: [] }),
            });
            personsApi.listPersons().subscribe({
              next: (rows: PersonListItem[]) =>
                patchOptions({ clubMemberNumbers: dedupeTokens(rows.map((p) => p.memberNumber)) }),
              error: () => patchOptions({ clubMemberNumbers: [] }),
            });
            referenceApi.listFlightCrewTypes().subscribe({
              next: (rows: FlightCrewTypeResponse[]) =>
                patchOptions({
                  flightCrewTypes: dedupeTokens(rows.map((c) => String(c.legacyId))),
                }),
              error: () => patchOptions({ flightCrewTypes: [] }),
            });
            prefetchMemberStatesWithoutSuggestionTokens();
          }),
        ),
      );
      return {
        select(id: string | null): void {
          patchState(store, { selectedId: id, selectedDetail: null });
        },
        clearSaveError(): void {
          patchState(store, { saveError: null, saveErrorKind: null });
        },
        loadAll,
        loadFilterTypes,
        loadUnitTypes,
        loadMatchListReferences,
        getDetail: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { isLoadingDetail: true, saveError: null })),
            switchMap((id) =>
              accountingApi.getAccountingRuleFilter(id).pipe(
                tapResponse({
                  next: (d: AccountingRuleFilterDetail) =>
                    patchState(store, {
                      selectedDetail: withDetailId(d),
                      isLoadingDetail: false,
                    }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, errorPatch(e), { isLoadingDetail: false }),
                }),
              ),
            ),
          ),
        ),
        create: rxMethod<AccountingRuleFilterWriteRequest>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap((req) =>
              accountingApi.createAccountingRuleFilter(req).pipe(
                tapResponse({
                  next: (d: AccountingRuleFilterDetail) => {
                    const detail = withDetailId(d);
                    patchState(store, addEntity(listItemFromDetail(detail)), {
                      selectedDetail: detail,
                    });
                    bus.next({ kind: 'accounting-rule-filter.created', id: detail.id });
                    loadAll();
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
                }),
              ),
            ),
          ),
        ),
        update: rxMethod<{ id: string; req: AccountingRuleFilterWriteRequest }>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap(({ id, req }) =>
              accountingApi.updateAccountingRuleFilter(id, req).pipe(
                tapResponse({
                  next: (d: AccountingRuleFilterDetail) => {
                    const detail = withDetailId(d);
                    patchState(store, setEntity(listItemFromDetail(detail)), {
                      selectedDetail: detail,
                    });
                    bus.next({ kind: 'accounting-rule-filter.updated', id: detail.id });
                    loadAll();
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
                }),
              ),
            ),
          ),
        ),
        delete: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap((id) =>
              accountingApi.deleteAccountingRuleFilter(id).pipe(
                tapResponse({
                  next: () => {
                    patchState(store, removeEntity(id), { selectedDetail: null });
                    bus.next({ kind: 'accounting-rule-filter.deleted', id: id });
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
                }),
              ),
            ),
          ),
        ),
      };
    },
  ),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.loadAll();
      store.loadFilterTypes();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<AccountingRuleFilterItem>([]), {
            selectedId: null,
            selectedDetail: null,
            lastRefreshedAt: null,
          });
        }
      });
    },
  }),
);

const accountingErrorRules: readonly SaveErrorRule<SaveErrorKind>[] = [
  {
    status: 409,
    outcome: () => ({
      saveError: 'This rule conflicts with an existing one. Adjust it and try again.',
      saveErrorKind: 'conflict',
    }),
  },
  {
    status: 403,
    outcome: () => ({
      saveError: 'You are not authorized to manage accounting rules.',
      saveErrorKind: 'forbidden',
    }),
  },
  {
    status: 404,
    outcome: () => ({
      saveError: 'This rule no longer exists.',
      saveErrorKind: 'not-found',
    }),
  },
];

function dedupeTokens(raw: readonly (string | null | undefined)[]): readonly string[] {
  const seen = new Set<string>();
  for (const value of raw) {
    const token = (value ?? '').trim();
    if (token !== '') seen.add(token);
  }
  return [...seen].sort((a, b) => a.localeCompare(b));
}

function errorPatch(e: HttpErrorResponse): { saveError: string; saveErrorKind: SaveErrorKind } {
  return classifyApiError(e, accountingErrorRules, (body, err) => {
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      return {
        saveError: body.field ? `${body.field}: ${body.message}` : body.message,
        saveErrorKind: 'other',
      };
    }
    return { saveError: err.message, saveErrorKind: 'other' };
  });
}
