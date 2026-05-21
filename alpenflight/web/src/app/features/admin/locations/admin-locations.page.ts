import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoDirective } from '@jsverse/transloco';

import type { ClubResponse, LocationListItem } from '@core/../api/generated/model';
import { ClubsService } from '@core/../api/generated/clubs/clubs.service';
import { LocationsAdminService } from '@core/../api/generated/locations-admin/locations-admin.service';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

/**
 * SYSTEM_ADMINISTRATOR cross-tenant Locations management. Pick a club from
 * the dropdown to load its Locations; per-row edit + delete with confirmation,
 * plus a `New location` affordance once a club is picked. Edit / create open
 * `AdminLocationsEditPage` which wires the regular Locations form to the
 * admin endpoints (server wraps every call in `Tenants.runAs(clubId)`).
 *
 * The route is gated by {@code sysadminGuard}; the server also gates every
 * call with {@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")} (S-049c F3).
 */
@Component({
  selector: 'af-admin-locations',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    AfSelectComponent,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <ng-container *transloco="let t; read: 'locations.admin'">
      <af-page>
        <af-page-header [title]="t('title')">
          @if (selectedClubId()) {
            <af-button
              type="primary"
              htmlType="button"
              data-testid="admin-locations-new"
              (clicked)="onNew()"
            >
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        <div
          class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50"
        >
          {{ t('banner') }}
        </div>

        <div class="mb-4 max-w-md">
          <label class="block text-sm text-slate-600 mb-1" for="admin-club-picker">
            {{ t('clubLabel') }}
          </label>
          <af-select
            id="admin-club-picker"
            data-testid="admin-club-picker"
            [options]="clubOptions()"
            [value]="selectedClubId()"
            (valueChange)="onClubPicked($event)"
            [placeholder]="clubsLoading() ? t('clubPlaceholderLoading') : t('clubPlaceholder')"
            [disabled]="clubsLoading()"
            [allowClear]="true"
          />
        </div>

        <af-page-error
          [message]="clubsError() ? t('clubsError') : null"
          (retry)="reloadClubs()"
          data-testid="admin-clubs-error"
        />

        @if (selectedClubId()) {
          <af-page-error
            [message]="locationsErrorKind() ? t(locationsErrorKind()!) : null"
            (retry)="reloadLocations()"
            data-testid="admin-locations-error"
          />

          <af-data-table
            data-testid="admin-locations-table"
            [items]="locations()"
            [loading]="locationsLoading()"
          >
            <ng-template #primary let-loc>
              <a
                class="text-slate-900 font-medium no-underline hover:text-brand-700"
                [routerLink]="['/admin/locations', selectedClubId(), loc.id, 'edit']"
                [attr.data-testid]="'admin-location-row-' + loc.id"
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
            </ng-template>
            <ng-template #meta let-loc>
              <div class="flex gap-2">
                <af-button
                  type="default"
                  htmlType="button"
                  [attr.data-testid]="'admin-location-edit-' + loc.id"
                  (clicked)="onEdit(loc)"
                >
                  {{ t('edit') }}
                </af-button>
                <af-button
                  type="default"
                  [danger]="true"
                  htmlType="button"
                  [attr.data-testid]="'admin-location-delete-' + loc.id"
                  (clicked)="confirmDelete(loc, t('deleteConfirm', { name: loc.locationName }))"
                >
                  {{ t('delete') }}
                </af-button>
              </div>
            </ng-template>
          </af-data-table>
        }
      </af-page>
    </ng-container>
  `,
})
export class AdminLocationsPage {
  private readonly clubsApi = inject(ClubsService);
  private readonly adminApi = inject(LocationsAdminService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly clubs = signal<readonly ClubResponse[]>([]);
  protected readonly clubsLoading = signal(false);
  protected readonly clubsError = signal<boolean>(false);
  protected readonly locations = signal<readonly LocationListItem[]>([]);
  protected readonly locationsLoading = signal(false);
  // Error state is a kind, not a literal — the template reads the localized
  // copy via `t(<kind>)`. `locationsError` for the GET, `deleteError` for the
  // failed mutation; both render through the same banner slot.
  protected readonly locationsErrorKind = signal<'locationsError' | 'deleteError' | null>(null);

  private readonly queryParams = toSignal(this.route.queryParamMap, { requireSync: true });
  protected readonly selectedClubId = computed(() => this.queryParams().get('clubId'));

  protected readonly clubOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.clubs().map((c) => ({
      value: c.id ?? '',
      label: c.name ?? c.clubKey ?? c.id ?? '',
    })),
  );

  constructor() {
    this.reloadClubs();
    const initial = this.selectedClubId();
    if (initial) {
      this.fetchLocations(initial);
    }
  }

  protected reloadClubs(): void {
    this.clubsLoading.set(true);
    this.clubsError.set(false);
    this.clubsApi.listClubs().subscribe({
      next: (items) => {
        this.clubs.set(items);
        this.clubsLoading.set(false);
      },
      error: () => {
        this.clubsLoading.set(false);
        this.clubsError.set(true);
      },
    });
  }

  protected onClubPicked(clubId: string | null): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { clubId: clubId ?? null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    this.locations.set([]);
    this.locationsErrorKind.set(null);
    if (clubId) {
      this.fetchLocations(clubId);
    }
  }

  protected reloadLocations(): void {
    const clubId = this.selectedClubId();
    if (clubId) {
      this.fetchLocations(clubId);
    }
  }

  protected onNew(): void {
    const clubId = this.selectedClubId();
    if (!clubId) return;
    this.router.navigate(['/admin/locations', clubId, 'new']);
  }

  protected onEdit(loc: LocationListItem): void {
    const clubId = this.selectedClubId();
    if (!clubId) return;
    this.router.navigate(['/admin/locations', clubId, loc.id, 'edit']);
  }

  protected confirmDelete(loc: LocationListItem, message: string): void {
    const clubId = this.selectedClubId();
    if (!clubId || typeof window === 'undefined') return;
    if (!window.confirm(message)) {
      return;
    }
    this.adminApi.adminDeleteLocation(clubId, loc.id).subscribe({
      next: () => this.fetchLocations(clubId),
      error: () => this.locationsErrorKind.set('deleteError'),
    });
  }

  private fetchLocations(clubId: string): void {
    this.locationsLoading.set(true);
    this.locationsErrorKind.set(null);
    this.adminApi.adminListLocations(clubId).subscribe({
      next: (items) => {
        this.locations.set(items);
        this.locationsLoading.set(false);
      },
      error: () => {
        this.locationsLoading.set(false);
        this.locationsErrorKind.set('locationsError');
      },
    });
  }
}
