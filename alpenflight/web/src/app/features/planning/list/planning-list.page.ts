import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { PlanningDayItem } from '../planning.store';
import { PlanningStore } from '../planning.store';

@Component({
  selector: 'af-planning-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    NzDropDownModule,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'planning'">
        <af-page-header [title]="t('title')">
          <af-button
            type="default"
            htmlType="button"
            (clicked)="goSetup()"
            data-testid="planning-setup-button"
          >
            <af-icon name="settings" [size]="16" class="mr-1.5 inline-flex align-middle" />
            {{ t('setup') }}
          </af-button>
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="button"
              (clicked)="goNew()"
              data-testid="planning-new-button"
            >
              <af-icon name="plus" [size]="16" class="mr-1.5 inline-flex align-middle" />
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.refresh()"
          data-testid="planning-error"
        />

        @if (store.deleteError(); as de) {
          <div
            class="mb-3 border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            data-testid="planning-delete-error"
          >
            {{ de }}
          </div>
        }

        <div class="overflow-x-auto border border-slate-200" data-testid="planning-list">
          <table class="w-full min-w-[760px] border-collapse text-sm">
            <thead class="bg-slate-50">
              <tr class="border-b border-slate-200 text-left text-xs text-slate-500">
                <th class="px-3 py-2.5 font-medium">{{ t('col.date') }}</th>
                <th class="px-3 py-2.5 font-medium">{{ t('col.location') }}</th>
                <th class="px-3 py-2.5 font-medium">{{ t('col.instructor') }}</th>
                <th class="px-3 py-2.5 font-medium">{{ t('col.towPilot') }}</th>
                <th class="px-3 py-2.5 font-medium">{{ t('col.flightOperator') }}</th>
                <th class="px-3 py-2.5 text-right font-medium">{{ t('col.reservations') }}</th>
                <th class="px-3 py-2.5"></th>
              </tr>
            </thead>
            <tbody>
              @for (day of store.entities(); track day.id) {
                <tr
                  class="border-b border-slate-200 last:border-b-0 hover:bg-slate-50"
                  [class.bg-brand-50]="isWeekend(day)"
                  [attr.data-testid]="'planning-row-' + day.id"
                  [attr.data-weekend]="isWeekend(day)"
                >
                  <td class="px-3 py-2.5">
                    <a
                      class="font-medium text-slate-900 no-underline hover:text-brand-700"
                      [routerLink]="['/planning', day.id, 'view']"
                    >
                      <span class="tabular">{{ formatDate(day.planningDate) }}</span>
                      <span
                        class="ml-2 text-xs"
                        [class.text-brand-600]="isWeekend(day)"
                        [class.text-slate-500]="!isWeekend(day)"
                        >{{ weekday(day.planningDate) }}</span
                      >
                    </a>
                  </td>
                  <td class="px-3 py-2.5">{{ locationName(day.locationId) }}</td>
                  <td class="px-3 py-2.5">{{ personName(day.instructorPersonId) }}</td>
                  <td class="px-3 py-2.5">{{ personName(day.towingPilotPersonId) }}</td>
                  <td class="px-3 py-2.5">{{ personName(day.flightOperatorPersonId) }}</td>
                  <td class="tabular px-3 py-2.5 text-right">
                    <span [attr.data-testid]="'planning-reservations-count-' + day.id">{{
                      day.numberOfAircraftReservations
                    }}</span>
                  </td>
                  <td class="px-3 py-2.5 text-right">
                    <button
                      type="button"
                      class="inline-flex h-8 w-8 items-center justify-center border-0 bg-transparent text-slate-500 cursor-pointer hover:bg-slate-100 hover:text-slate-900"
                      nz-dropdown
                      [nzDropdownMenu]="rowMenu"
                      nzTrigger="click"
                      nzPlacement="bottomRight"
                      [attr.aria-label]="t('rowActions')"
                      [attr.data-testid]="'planning-kebab-' + day.id"
                      (click)="$event.stopPropagation()"
                    >
                      <af-icon name="more-vertical" [size]="18" />
                    </button>
                    <nz-dropdown-menu #rowMenu="nzDropdownMenu">
                      <ul
                        class="m-0 list-none border border-slate-200 bg-white p-1 min-w-[10rem]"
                        role="menu"
                      >
                        <li role="none">
                          <a
                            role="menuitem"
                            class="flex w-full items-center gap-2 px-2.5 py-1.5 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                            [routerLink]="['/planning', day.id, 'view']"
                            [attr.data-testid]="'planning-view-' + day.id"
                          >
                            <af-icon name="eye" [size]="14" />
                            <span>{{ t('view') }}</span>
                          </a>
                        </li>
                        @if (day.canUpdateRecord) {
                          <li role="none">
                            <a
                              role="menuitem"
                              class="flex w-full items-center gap-2 px-2.5 py-1.5 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                              [routerLink]="['/planning', day.id, 'edit']"
                              [attr.data-testid]="'planning-edit-' + day.id"
                            >
                              <af-icon name="pencil" [size]="14" />
                              <span>{{ t('edit') }}</span>
                            </a>
                          </li>
                        }
                        @if (day.canDeleteRecord) {
                          <li role="none">
                            <button
                              type="button"
                              role="menuitem"
                              class="flex w-full items-center gap-2 border-0 bg-transparent px-2.5 py-1.5 text-[15px] text-red-600 cursor-pointer text-left hover:bg-slate-50"
                              (click)="askDelete(day)"
                              [attr.data-testid]="'planning-delete-' + day.id"
                            >
                              <af-icon name="trash-2" [size]="14" />
                              <span>{{ t('delete') }}</span>
                            </button>
                          </li>
                        }
                      </ul>
                    </nz-dropdown-menu>
                  </td>
                </tr>
              }
              @if (store.isEmpty() && !store.isLoading()) {
                <tr>
                  <td
                    class="px-3 py-4 text-sm text-slate-500"
                    colspan="7"
                    data-testid="planning-empty"
                  >
                    {{ t('empty') }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (pendingDelete(); as pd) {
          <div
            class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40"
            data-testid="planning-delete-dialog"
          >
            <div class="w-[22rem] max-w-[90vw] border border-slate-200 bg-white shadow-md">
              <div class="border-b border-slate-200 px-4 py-3 font-medium text-slate-900">
                {{ t('deleteTitle') }}
              </div>
              <div class="px-4 py-3 text-sm text-slate-700">
                {{ t('deleteConfirm', { date: formatDate(pd.planningDate) }) }}
              </div>
              <div class="flex justify-end gap-2 border-t border-slate-200 px-4 py-3">
                <af-button
                  type="default"
                  htmlType="button"
                  (clicked)="cancelDelete()"
                  data-testid="planning-delete-cancel"
                >
                  {{ t('cancel') }}
                </af-button>
                <af-button
                  type="primary"
                  htmlType="button"
                  (clicked)="confirmDelete()"
                  data-testid="planning-delete-confirm"
                >
                  {{ t('delete') }}
                </af-button>
              </div>
            </div>
          </div>
        }
      </ng-container>
    </af-page>
  `,
})
export class PlanningListPage {
  protected readonly store = inject(PlanningStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);

  protected readonly canMutate = computed(
    () => this.session.isClubAdmin() || this.session.isSystemAdmin(),
  );

  protected readonly pendingDelete = signal<PlanningDayItem | null>(null);

  protected isWeekend(day: PlanningDayItem): boolean {
    const dow = this.dayOfWeek(day.planningDate);
    return dow === 0 || dow === 6;
  }

  protected locationName(id: string): string {
    return this.store.locationNameById()[id] ?? id;
  }

  protected personName(id: string | undefined): string {
    if (!id) return '—';
    return this.store.personNameById()[id] ?? id;
  }

  protected formatDate(iso: string): string {
    const [y, m, d] = iso.split('-');
    return y && m && d ? `${d}.${m}.${y}` : iso;
  }

  protected weekday(iso: string): string {
    const dow = this.dayOfWeek(iso);
    return WEEKDAYS_DE[dow] ?? '';
  }

  private dayOfWeek(iso: string): number {
    const [y, m, d] = iso.split('-').map(Number);
    if (!y || !m || !d) return -1;
    return new Date(y, m - 1, d).getDay();
  }

  protected goNew(): void {
    void this.router.navigateByUrl('/planning/new/edit');
  }

  protected goSetup(): void {
    void this.router.navigateByUrl('/planningsetup');
  }

  protected askDelete(day: PlanningDayItem): void {
    this.store.clearDeleteError();
    this.pendingDelete.set(day);
  }

  protected cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  protected confirmDelete(): void {
    const pd = this.pendingDelete();
    if (!pd) return;
    this.store.delete(pd.id);
    this.pendingDelete.set(null);
  }
}

const WEEKDAYS_DE: readonly string[] = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];
