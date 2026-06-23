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
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { EmailTemplatesService } from '@api/generated/email-templates/email-templates.service';
import type { EmailTemplateListItem, EmailTemplateSaveRequest } from '@api/generated/model';

import { classifyApiError, genericSaveErrorMessage, type SaveErrorRule } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type SaveErrorKind = 'forbidden' | 'validation' | 'other';

interface EmailTemplatesState {
  items: readonly EmailTemplateListItem[];
  isLoading: boolean;
  isSaving: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialState: EmailTemplatesState = {
  items: [],
  isLoading: false,
  isSaving: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

interface SaveArgs {
  templateKey: string;
  languageLocale: string;
  request: EmailTemplateSaveRequest;
}

interface ResetArgs {
  templateKey: string;
  languageLocale: string;
}

export const EmailTemplatesStore = signalStore(
  { providedIn: 'root' },
  withState<EmailTemplatesState>(initialState),
  withComputed(({ items, loadError, saveError }) => ({
    isEmpty: computed(() => items().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
  })),
  withMethods((store, api = inject(EmailTemplatesService)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          api.listTemplates().pipe(
            tapResponse({
              next: (items: EmailTemplateListItem[]) =>
                patchState(store, {
                  items,
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
    return {
      clearSaveError(): void {
        patchState(store, { saveError: null, saveErrorKind: null });
      },
      load,
      save: rxMethod<SaveArgs>(
        pipe(
          tap(() => patchState(store, { isSaving: true, saveError: null, saveErrorKind: null })),
          switchMap(({ templateKey, languageLocale, request }) =>
            api.customize(templateKey, languageLocale, request).pipe(
              tapResponse({
                next: () => {
                  patchState(store, { isSaving: false });
                  load();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, { isSaving: false, ...errorPatch(e) }),
              }),
            ),
          ),
        ),
      ),
      reset: rxMethod<ResetArgs>(
        pipe(
          tap(() => patchState(store, { isSaving: true, saveError: null, saveErrorKind: null })),
          switchMap(({ templateKey, languageLocale }) =>
            api.reset(templateKey, languageLocale).pipe(
              tapResponse({
                next: () => {
                  patchState(store, { isSaving: false });
                  load();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, { isSaving: false, ...errorPatch(e) }),
              }),
            ),
          ),
        ),
      ),
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.load();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, { items: [], lastRefreshedAt: null });
        }
      });
    },
  }),
);

const writeErrorRules: readonly SaveErrorRule<SaveErrorKind>[] = [
  {
    status: 403,
    outcome: (b) => ({
      saveError: b.detail ?? 'You are not authorized to customize email templates for this club.',
      saveErrorKind: 'forbidden',
    }),
  },
  {
    status: 400,
    outcome: (b, e) => ({
      saveError: genericSaveErrorMessage(b, e),
      saveErrorKind: 'validation',
    }),
  },
];

function errorPatch(e: HttpErrorResponse): { saveError: string; saveErrorKind: SaveErrorKind } {
  return classifyApiError(e, writeErrorRules, (body, err) => ({
    saveError: genericSaveErrorMessage(body, err),
    saveErrorKind: 'other',
  }));
}
