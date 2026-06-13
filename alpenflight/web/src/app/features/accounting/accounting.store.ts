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
import type {
  AccountingRuleFilterDetail,
  AccountingRuleFilterListItem,
  AccountingRuleFilterTypeResponse,
  AccountingRuleFilterWriteRequest,
  AccountingUnitTypeResponse,
} from '@api/generated/model';

import { classifyApiError, type SaveErrorRule } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type AccountingRuleFilterItem = AccountingRuleFilterListItem & { id: string };
export type AccountingRuleFilterDetailLoaded = AccountingRuleFilterDetail & { id: string };

// 409 → a sort-indicator / name collision the user can retry past; 403 → the
// CLUB_ADMINISTRATOR gate; 404 → a cross-tenant or deleted row (the @TenantId
// finder never returns another club's filter). Everything else falls through to
// the shared generic tail in `classifyApiError`.
export type SaveErrorKind = 'conflict' | 'forbidden' | 'not-found' | 'other';

interface AccountingExtraState {
  selectedId: string | null;
  selectedDetail: AccountingRuleFilterDetailLoaded | null;
  // Reference catalog (T-07): the filter types, keyed for the list's type
  // column + (T-12) the edit form's section-driving select.
  filterTypes: readonly AccountingRuleFilterTypeResponse[];
  // Unit-type catalog (T-07): feeds the edit form's article-target section
  // AccountingUnitType select (T-12). Lazy-loaded on edit-form entry.
  accountingUnitTypes: readonly AccountingUnitTypeResponse[];
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

/**
 * Project the detail payload onto the list-row shape for an optimistic
 * post-save patch. `target` is a server-derived display string
 * (`{recipientName} ({memberNumber})` / `{articleNumber} ({deliveryLineText})`,
 * T-05) that the client cannot reconstruct, so the optimistic row carries an
 * empty `target`; the `loadAll()` after the mutation settles it to the
 * authoritative value.
 */
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
                // A failed catalog load leaves the type column unresolved — it
                // never blocks the list's identity columns or the edit form.
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
                // A failed catalog load leaves the unit-type select empty — it
                // never blocks the edit form (the unit-type is optional).
                error: () => patchState(store, { accountingUnitTypes: [] }),
              }),
            ),
          ),
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

// Ordered classification rules (J-26 T-22 shared `classifyApiError`): map the
// distinct HTTP statuses the AccountingRuleFilter API raises to a `SaveErrorKind`
// + a terse, sentence-case message. 409 (collision) → conflict; 403 (the
// CLUB_ADMINISTRATOR gate) → forbidden; 404 (cross-tenant / deleted) → not-found.
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
