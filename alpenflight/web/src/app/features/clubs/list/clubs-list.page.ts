import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import type { Club } from '../clubs.store';
import { ClubsStore } from '../clubs.store';

@Component({
  selector: 'af-clubs-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    NzDropDownModule,
    RouterLink,
  ],
  template: `
    <af-page>
      <af-page-header title="Clubs">
        <af-button type="primary" htmlType="button" (clicked)="router.navigateByUrl('/clubs/new')">
          New club
        </af-button>
      </af-page-header>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="clubs-error"
      />

      <af-data-table
        data-testid="clubs-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-club>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/clubs', club.id, 'edit']"
            [attr.data-testid]="'club-row-' + club.slug"
          >
            {{ club.name }}
          </a>
        </ng-template>
        <ng-template #secondary let-club>
          <span class="tabular">{{ club.slug }}</span> ·
          <span class="tabular">{{ club.clubKey }}</span>
          @if (club.publicRegistrationEnabled) {
            <span class="inline-block ml-2 text-xs px-2 py-0.5 bg-brand-50 text-brand-700"
              >Public registration</span
            >
          }
        </ng-template>
        <ng-template #meta let-club>
          <button
            type="button"
            class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-50"
            nz-dropdown
            [nzDropdownMenu]="rowMenu"
            nzTrigger="click"
            nzPlacement="bottomRight"
            [attr.aria-label]="'Actions for ' + club.name"
            [attr.data-testid]="'club-kebab-' + club.slug"
            (click)="$event.stopPropagation()"
          >
            <af-icon name="more-vertical" [size]="18" />
          </button>
          <nz-dropdown-menu #rowMenu="nzDropdownMenu">
            <ul
              class="list-none m-0 p-1 min-w-[10rem] bg-white border border-slate-200"
              role="menu"
            >
              <li role="none">
                <a
                  role="menuitem"
                  class="flex items-center gap-2 w-full py-1.5 px-2.5 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                  [routerLink]="['/clubs', club.id, 'edit']"
                >
                  <af-icon name="pencil" [size]="14" />
                  <span>Edit</span>
                </a>
              </li>
              <li role="none">
                <button
                  type="button"
                  role="menuitem"
                  class="flex items-center gap-2 w-full py-1.5 px-2.5 bg-transparent border-0 text-[15px] text-red-600 cursor-pointer text-left hover:bg-slate-50"
                  (click)="confirmDelete(club)"
                >
                  <af-icon name="trash-2" [size]="14" />
                  <span>Delete</span>
                </button>
              </li>
            </ul>
          </nz-dropdown-menu>
        </ng-template>
      </af-data-table>
    </af-page>
  `,
})
export class ClubsListPage {
  protected readonly store = inject(ClubsStore);
  protected readonly router = inject(Router);

  protected confirmDelete(club: Club): void {
    // Lightweight confirm for v1; replace with nz-modal in a follow-up.
    if (typeof window === 'undefined' || !club.id) return;
    if (window.confirm(`Delete "${club.name}"? This cannot be undone.`)) {
      this.store.delete(club.id);
    }
  }
}
