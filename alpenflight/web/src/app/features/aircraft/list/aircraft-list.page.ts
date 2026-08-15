import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { AircraftItem, AircraftTypeFilter } from '../aircraft.store';
import { AircraftStore } from '../aircraft.store';

const TYPE_FILTER_OPTIONS: readonly AfSelectOption<AircraftTypeFilter>[] = [
  { value: 'ALL', label: 'All types' },
  { value: 'GLIDER', label: 'Gliders' },
  { value: 'TOWING', label: 'Towing aircraft' },
  { value: 'MOTOR', label: 'Motor aircraft' },
];

@Component({
  selector: 'af-aircraft-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfFormFieldComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    AfSelectComponent,
    NzDropDownModule,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'aircraft'">
        <af-page-header [title]="t('title')">
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="button"
              (clicked)="router.navigateByUrl('/aircraft/new')"
              data-testid="aircraft-new-button"
            >
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        @if (canMutate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50"
            data-testid="aircraft-blast-radius-banner"
          >
            {{ t('blastRadiusBanner') }}
          </div>
        } @else {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
            data-testid="aircraft-readonly-banner"
          >
            {{ t('list.readonlyBanner') }}
          </div>
        }

        <div class="mb-4 max-w-xs">
          <af-form-field [label]="t('list.typeFilter.label')" for="AircraftTypeFilter">
            <af-select
              inputId="AircraftTypeFilter"
              [value]="store.typeFilter()"
              (valueChange)="onTypeFilterChange($event)"
              [options]="typeFilterOptions"
              data-testid="aircraft-type-filter"
            />
          </af-form-field>
        </div>
      </ng-container>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll(store.typeFilter())"
        data-testid="aircraft-error"
      />

      <af-data-table
        data-testid="aircraft-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-ac>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/aircraft', ac.id, 'edit']"
            [attr.data-testid]="'aircraft-row-' + ac.id"
          >
            <span class="tabular">{{ ac.immatriculation }}</span>
          </a>
        </ng-template>
        <ng-template #secondary let-ac>
          <ng-container *transloco="let t; read: 'aircraft'">
            @if (ac.competitionSign) {
              <span>{{ ac.competitionSign }}</span>
              <span> · </span>
            }
            <span>{{ ac.aircraftTypeCode }}</span>
            @if (ac.aircraftModel || ac.manufacturerName) {
              <span> · </span>
              <span [attr.data-testid]="'aircraft-model-' + ac.id">
                @if (ac.manufacturerName) {
                  {{ ac.manufacturerName }}
                }
                @if (ac.aircraftModel) {
                  {{ ac.aircraftModel }}
                }
              </span>
            }
            @if (ac.nrOfSeats !== null && ac.nrOfSeats !== undefined) {
              <span> · </span>
              <span [attr.data-testid]="'aircraft-seats-' + ac.id">
                {{ t('list.columns.seats', { count: ac.nrOfSeats }) }}
              </span>
            }
          </ng-container>
          @if (ac.currentStateCode) {
            <span> · </span>
            <span
              class="inline-block ml-1 text-xs px-2 py-0.5"
              [class.bg-emerald-50]="ac.currentStateFlyable === true"
              [class.text-emerald-700]="ac.currentStateFlyable === true"
              [class.bg-amber-50]="ac.currentStateFlyable === false"
              [class.text-amber-700]="ac.currentStateFlyable === false"
              [class.bg-slate-50]="
                ac.currentStateFlyable === null || ac.currentStateFlyable === undefined
              "
              [class.text-slate-600]="
                ac.currentStateFlyable === null || ac.currentStateFlyable === undefined
              "
              [attr.data-testid]="'aircraft-state-' + ac.id"
            >
              {{ ac.currentStateCode }}
            </span>
          }
          @if (ac.isTowingAircraft) {
            <span class="inline-block ml-2 text-xs px-2 py-0.5 bg-brand-50 text-brand-700">
              Towing
            </span>
          }
        </ng-template>
        <ng-template #meta let-ac>
          @if (canMutate()) {
            <button
              type="button"
              class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-50"
              nz-dropdown
              [nzDropdownMenu]="rowMenu"
              nzTrigger="click"
              nzPlacement="bottomRight"
              [attr.aria-label]="'Actions for ' + ac.immatriculation"
              [attr.data-testid]="'aircraft-kebab-' + ac.id"
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
                    [routerLink]="['/aircraft', ac.id, 'edit']"
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
                    (click)="confirmDelete(ac)"
                    [attr.data-testid]="'aircraft-delete-' + ac.id"
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
export class AircraftListPage {
  protected readonly store = inject(AircraftStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  protected readonly canMutate = this.session.isClubAdmin;
  protected readonly typeFilterOptions = TYPE_FILTER_OPTIONS;

  protected onTypeFilterChange(value: AircraftTypeFilter | null): void {
    this.store.setTypeFilter(value ?? 'ALL');
  }

  protected confirmDelete(ac: AircraftItem): void {
    if (typeof window === 'undefined' || !ac.id) return;
    const message = this.transloco.translate('aircraft.deleteConfirm', {
      immatriculation: ac.immatriculation,
    });
    if (window.confirm(message)) {
      this.store.delete(ac.id);
    }
  }
}
