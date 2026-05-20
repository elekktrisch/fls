import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { LocationItem } from '../locations.store';
import { LocationsStore } from '../locations.store';

@Component({
  selector: 'af-locations-list',
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
      <af-page-header title="Locations">
        @if (canMutate()) {
          <af-button
            type="primary"
            htmlType="button"
            (clicked)="router.navigateByUrl('/locations/new')"
          >
            New location
          </af-button>
        }
      </af-page-header>

      @if (canMutate()) {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50"
          data-testid="locations-blast-radius-banner"
        >
          Reference data — changes apply to all clubs.
        </div>
      } @else {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
          data-testid="locations-readonly-banner"
        >
          Reference data — changes apply to all clubs and are managed by the system administrator.
        </div>
      }

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="locations-error"
      />

      <af-data-table
        data-testid="locations-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-loc>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/locations', loc.id, 'edit']"
            [attr.data-testid]="'location-row-' + loc.id"
          >
            {{ loc.locationName }}
          </a>
        </ng-template>
        <ng-template #secondary let-loc>
          @if (loc.icaoCode) {
            <span class="tabular">{{ loc.icaoCode }}</span>
          }
          @if (loc.icaoCode && loc.locationTypeCode) {
            <span> · </span>
          }
          @if (loc.locationTypeCode) {
            <span>{{ loc.locationTypeCode }}</span>
          }
          @if (loc.isFastEntryRecord) {
            <span class="inline-block ml-2 text-xs px-2 py-0.5 bg-brand-50 text-brand-700">
              Fast entry
            </span>
          }
        </ng-template>
        <ng-template #meta let-loc>
          @if (canMutate()) {
            <button
              type="button"
              class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-50"
              nz-dropdown
              [nzDropdownMenu]="rowMenu"
              nzTrigger="click"
              nzPlacement="bottomRight"
              [attr.aria-label]="'Actions for ' + loc.locationName"
              [attr.data-testid]="'location-kebab-' + loc.id"
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
                    [routerLink]="['/locations', loc.id, 'edit']"
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
                    (click)="confirmDelete(loc)"
                    [attr.data-testid]="'location-delete-' + loc.id"
                  >
                    <af-icon name="trash-2" [size]="14" />
                    <span>Delete</span>
                  </button>
                </li>
              </ul>
            </nz-dropdown-menu>
          }
        </ng-template>
      </af-data-table>
    </af-page>
  `,
})
export class LocationsListPage {
  protected readonly store = inject(LocationsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  protected readonly canMutate = computed(() => this.session.isSystemAdmin());

  protected confirmDelete(loc: LocationItem): void {
    if (typeof window === 'undefined' || !loc.id) return;
    if (window.confirm(`Delete "${loc.locationName}"? This cannot be undone.`)) {
      this.store.delete(loc.id);
    }
  }
}
