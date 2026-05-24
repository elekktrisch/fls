import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { FlightTypeItem } from '../flight-types.store';
import { FlightTypesStore } from '../flight-types.store';

@Component({
  selector: 'af-flight-types-list',
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
      <af-page-header title="Flight types">
        @if (canMutate()) {
          <af-button
            type="primary"
            htmlType="button"
            (clicked)="router.navigateByUrl('/flight-types/new')"
            data-testid="flight-types-new-button"
          >
            New flight type
          </af-button>
        }
      </af-page-header>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="flight-types-error"
      />

      <af-data-table
        data-testid="flight-types-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-ft>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/flight-types', ft.id, 'edit']"
            [attr.data-testid]="'flight-types-row-' + ft.id"
          >
            {{ ft.flightTypeName }}
          </a>
        </ng-template>
        <ng-template #secondary let-ft>
          @if (ft.flightCode) {
            <span class="tabular">{{ ft.flightCode }}</span>
            <span> · </span>
          }
          @if (ft.isForGliderFlights) {
            <span class="inline-block ml-1 text-xs px-2 py-0.5 bg-slate-50 text-slate-700">
              Glider
            </span>
          }
          @if (ft.isForTowFlights) {
            <span class="inline-block ml-1 text-xs px-2 py-0.5 bg-slate-50 text-slate-700">
              Tow
            </span>
          }
          @if (ft.isForMotorFlights) {
            <span class="inline-block ml-1 text-xs px-2 py-0.5 bg-slate-50 text-slate-700">
              Motor
            </span>
          }
          @if (ft.isFlightCostBalanceSelectable) {
            <span class="inline-block ml-2 text-xs px-2 py-0.5 bg-brand-50 text-brand-700">
              Cost-balance
            </span>
          }
        </ng-template>
        <ng-template #meta let-ft>
          @if (canMutate()) {
            <button
              type="button"
              class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-50"
              nz-dropdown
              [nzDropdownMenu]="rowMenu"
              nzTrigger="click"
              nzPlacement="bottomRight"
              [attr.aria-label]="'Actions for ' + ft.flightTypeName"
              [attr.data-testid]="'flight-types-kebab-' + ft.id"
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
                    [routerLink]="['/flight-types', ft.id, 'edit']"
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
                    (click)="confirmDelete(ft)"
                    [attr.data-testid]="'flight-types-delete-' + ft.id"
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
export class FlightTypesListPage {
  protected readonly store = inject(FlightTypesStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  protected readonly canMutate = this.session.isClubAdmin;

  protected confirmDelete(ft: FlightTypeItem): void {
    if (typeof window === 'undefined' || !ft.id) return;
    if (window.confirm(`Delete flight type "${ft.flightTypeName}"? This cannot be undone.`)) {
      this.store.delete(ft.id);
    }
  }
}
