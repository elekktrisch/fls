import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import { PersonsStore } from '../persons.store';

@Component({
  selector: 'af-persons-list',
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
  ],
  template: `
    <af-page>
      <af-page-header title="Persons">
        @if (canMutate()) {
          <af-button
            type="primary"
            htmlType="button"
            (clicked)="router.navigateByUrl('/persons/new')"
            data-testid="persons-new-button"
          >
            New person
          </af-button>
        }
      </af-page-header>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="persons-error"
      />

      <af-data-table
        data-testid="persons-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-p>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/persons', p.id, 'edit']"
            [attr.data-testid]="'person-row-' + p.id"
          >
            <span>{{ p.lastname }}, {{ p.firstname }}</span>
          </a>
        </ng-template>
        <ng-template #secondary let-p>
          @if (p.email) {
            <span class="text-slate-600">{{ p.email }}</span>
          }
          @if (p.memberStateName) {
            <span class="inline-block ml-2 text-xs px-2 py-0.5 bg-slate-50 text-slate-600">
              {{ p.memberStateName }}
            </span>
          }
          @if (p.memberNumber) {
            <span class="inline-block ml-2 text-xs px-2 py-0.5 bg-slate-50 text-slate-600 tabular">
              #{{ p.memberNumber }}
            </span>
          }
        </ng-template>
        <ng-template #meta let-p>
          @if (!p.isActive) {
            <span class="text-xs text-slate-500">Inactive</span>
          }
        </ng-template>
      </af-data-table>
    </af-page>
  `,
})
export class PersonsListPage {
  protected readonly store = inject(PersonsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  protected readonly canMutate = this.session.isClubAdmin;
}
