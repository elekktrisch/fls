import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import { AccountingStore } from '../accounting.store';

@Component({
  selector: 'af-accounting-list',
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
      <ng-container *transloco="let t; read: 'accounting'">
        <af-page-header [title]="t('title')">
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="button"
              (clicked)="router.navigateByUrl('/accountingrules/new')"
              data-testid="accounting-rules-new-button"
            >
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        @if (!canMutate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
            data-testid="accounting-rules-readonly-banner"
          >
            {{ t('list.readonlyBanner') }}
          </div>
        }

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.loadAll()"
          data-testid="accounting-rules-error"
        />

        <af-data-table
          data-testid="accounting-rules-table"
          [items]="store.entities()"
          [loading]="store.isLoading()"
        >
          <ng-template #primary let-f>
            <a
              class="text-slate-900 font-medium no-underline hover:text-brand-700"
              [routerLink]="['/accountingrules', f.id, 'edit']"
              [attr.data-testid]="'accounting-rules-row-' + f.id"
            >
              {{ f.ruleFilterName }}
            </a>
          </ng-template>
          <ng-template #secondary let-f>
            @if (typeName(f.filterTypeId); as name) {
              <span [attr.data-testid]="'accounting-rules-type-' + f.id">{{ name }}</span>
              <span> · </span>
            }
            @if (f.active) {
              <span class="text-emerald-700">{{ t('list.columns.active') }}</span>
            } @else {
              <span class="text-slate-400">{{ t('list.columns.active') }}</span>
            }
            @if (f.target) {
              <span> · </span>
              <span [attr.data-testid]="'accounting-rules-target-' + f.id">{{ f.target }}</span>
            }
          </ng-template>
        </af-data-table>
      </ng-container>
    </af-page>
  `,
})
export class AccountingListPage {
  protected readonly store = inject(AccountingStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);

  protected readonly canMutate = this.session.isClubAdmin;

  protected typeName(filterTypeId: string): string | null {
    return this.store.filterTypeNameById().get(filterTypeId) ?? null;
  }
}
