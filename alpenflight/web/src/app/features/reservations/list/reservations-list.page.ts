import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent, type PageChange } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { ReservationItem } from '../reservations.store';
import { ReservationsStore } from '../reservations.store';

/**
 * `/reservations` list — the paged aircraft-reservation table (J-5 T-08).
 *
 * Mirrors the aircraft list seam: NgRx Signal Store (`withEntities` + paged
 * read), `<af-data-table>`, kebab row actions. Cross-module labels
 * (immatriculation, pilot, location) are NOT server-denormalised (ADR 0023) —
 * they're decorated client-side from the store's picker label maps. The seven
 * committed columns (legacy `reservations-table.html` parity) are exposed as
 * the per-column `data-testid`s the T-01 spec stub locks.
 */
@Component({
  selector: 'af-reservations-list',
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
    DatePipe,
    NzDropDownModule,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'reservations'">
        <af-page-header [title]="t('title')">
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="button"
              (clicked)="router.navigateByUrl('/reservations/new')"
              data-testid="reservations-new-button"
            >
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.refresh()"
          data-testid="reservations-error"
        />

        <af-data-table
          data-testid="reservations-table"
          [items]="store.entities()"
          [loading]="store.isLoading()"
          [showPagination]="true"
          [pageSize]="store.pageSize()"
          [total]="store.total()"
          (pageChange)="onPageChange($event)"
        >
          <ng-template #primary let-r>
            <a
              class="text-slate-900 font-medium no-underline hover:text-brand-700"
              [routerLink]="['/reservations', r.id, 'edit']"
              [attr.data-testid]="'reservations-row-' + r.id"
            >
              <span class="tabular" [attr.data-testid]="'reservations-immat-' + r.id">
                {{ immat(r) }}
              </span>
            </a>
          </ng-template>
          <ng-template #secondary let-r>
            <span class="tabular" [attr.data-testid]="'reservations-start-' + r.id">
              {{ r.start | date: 'dd.MM.yyyy HH:mm' }}
            </span>
            <span> – </span>
            <span class="tabular" [attr.data-testid]="'reservations-end-' + r.id">
              {{ r.end | date: 'dd.MM.yyyy HH:mm' }}
            </span>
            <span> · </span>
            <span [attr.data-testid]="'reservations-pilot-' + r.id">{{ pilot(r) }}</span>
            <span> · </span>
            <span [attr.data-testid]="'reservations-location-' + r.id">{{ location(r) }}</span>
            <span> · </span>
            <span [attr.data-testid]="'reservations-type-' + r.id">
              {{ r.reservationTypeName || '—' }}
            </span>
            <span> · </span>
            <span [attr.data-testid]="'reservations-allday-' + r.id">
              @if (r.isAllDay) {
                {{ t('allDay') }}
              } @else {
                {{ t('timed') }}
              }
            </span>
          </ng-template>
          <ng-template #meta let-r>
            @if (canMutate()) {
              <button
                type="button"
                class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-50"
                nz-dropdown
                [nzDropdownMenu]="rowMenu"
                nzTrigger="click"
                nzPlacement="bottomRight"
                [attr.aria-label]="t('rowActions')"
                [attr.data-testid]="'reservations-kebab-' + r.id"
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
                      [routerLink]="['/reservations', r.id, 'edit']"
                    >
                      <af-icon name="pencil" [size]="14" />
                      <span>{{ t('edit') }}</span>
                    </a>
                  </li>
                  <li role="none">
                    <button
                      type="button"
                      role="menuitem"
                      class="flex items-center gap-2 w-full py-1.5 px-2.5 bg-transparent border-0 text-[15px] text-red-600 cursor-pointer text-left hover:bg-slate-50"
                      (click)="confirmDelete(r)"
                      [attr.data-testid]="'reservations-delete-' + r.id"
                    >
                      <af-icon name="trash-2" [size]="14" />
                      <span>{{ t('delete') }}</span>
                    </button>
                  </li>
                </ul>
              </nz-dropdown-menu>
            }
          </ng-template>
        </af-data-table>
      </ng-container>
    </af-page>
  `,
})
export class ReservationsListPage {
  protected readonly store = inject(ReservationsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  protected readonly canMutate = computed(
    () => this.session.isClubAdmin() || this.session.isSystemAdmin(),
  );

  protected immat(r: ReservationItem): string {
    return this.store.immatById()[r.aircraftId] ?? r.aircraftId;
  }

  protected pilot(r: ReservationItem): string {
    return this.store.pilotNameById()[r.pilotPersonId] ?? r.pilotPersonId;
  }

  protected location(r: ReservationItem): string {
    return this.store.locationNameById()[r.locationId] ?? r.locationId;
  }

  protected onPageChange(change: PageChange): void {
    this.store.goToPage(change.page);
  }

  protected confirmDelete(r: ReservationItem): void {
    if (typeof window === 'undefined' || !r.id) return;
    const message = this.transloco.translate('reservations.deleteConfirm', {
      immatriculation: this.immat(r),
    });
    if (window.confirm(message)) {
      this.store.delete(r.id);
    }
  }
}
