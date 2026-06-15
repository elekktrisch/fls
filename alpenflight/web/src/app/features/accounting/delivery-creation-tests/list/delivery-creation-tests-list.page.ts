import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../../core/session/session.store';
import { DeliveryCreationTestsStore } from '../delivery-creation-tests.store';

/**
 * DeliveryCreationTest list (`/deliverycreationtests`). The rules-engine harness
 * surface. Columns: name · active · last result (✓ pass / ✗ fail / — never run).
 * A "new" entry point and per-row navigation into the edit form (T-17). Every
 * harness endpoint is CLUB_ADMINISTRATOR-gated on the server, so the mutation
 * affordance hides for a non-admin (the row identity columns still render).
 */
@Component({
  selector: 'af-delivery-creation-tests-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'deliveryCreationTests'">
        <af-page-header [title]="t('title')">
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="button"
              (clicked)="router.navigateByUrl('/deliverycreationtests/new')"
              data-testid="dct-new-button"
            >
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        @if (!canMutate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
            data-testid="dct-readonly-banner"
          >
            {{ t('list.readonlyBanner') }}
          </div>
        }

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.loadAll()"
          data-testid="dct-error"
        />

        <af-data-table
          data-testid="dct-table"
          [items]="store.entities()"
          [loading]="store.isLoading()"
        >
          <ng-template #primary let-test>
            <a
              class="text-slate-900 font-medium no-underline hover:text-brand-700"
              [routerLink]="['/deliverycreationtests', test.id, 'edit']"
              [attr.data-testid]="'dct-row-' + test.id"
            >
              {{ test.testName }}
            </a>
          </ng-template>
          <ng-template #secondary let-test>
            @if (test.active) {
              <span class="text-emerald-700">{{ t('list.columns.active') }}</span>
            } @else {
              <span class="text-slate-400">{{ t('list.columns.active') }}</span>
            }
            <span> · </span>
            <span
              [class]="resultClass(test.lastTestSuccessful)"
              [attr.title]="resultTitle(t, test.lastTestSuccessful)"
              [attr.data-testid]="'dct-row-result-' + test.id"
            >
              {{ resultGlyph(test.lastTestSuccessful) }}
            </span>
          </ng-template>
        </af-data-table>
      </ng-container>
    </af-page>
  `,
})
export class DeliveryCreationTestsListPage {
  protected readonly store = inject(DeliveryCreationTestsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);

  protected readonly canMutate = this.session.isClubAdmin;

  protected resultGlyph(successful: boolean | undefined): string {
    if (successful === undefined) return '—';
    return successful ? '✓' : '✗';
  }

  protected resultClass(successful: boolean | undefined): string {
    if (successful === undefined) return 'text-slate-400';
    return successful ? 'text-emerald-700' : 'text-red-700';
  }

  protected resultTitle(t: (key: string) => string, successful: boolean | undefined): string {
    if (successful === undefined) return t('list.results.never');
    return successful ? t('list.results.pass') : t('list.results.fail');
  }
}
