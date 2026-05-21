import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';

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
 * the dropdown to load its Locations; per-row delete confirmation. Create
 * and edit affordances are deferred — sysadmins fix-up-only via this surface
 * today; full CRUD parity tracked as a follow-up. The route is gated by
 * {@code sysadminGuard}; the server also gates every call with
 * {@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")} (S-049c F3).
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
  ],
  template: `
    <af-page>
      <af-page-header title="Locations admin (cross-tenant)" />

      <div class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50">
        Cross-tenant view — pick a club to operate on its Locations. Standard
        per-club management is at /locations.
      </div>

      <div class="mb-4 max-w-md">
        <label class="block text-sm text-slate-600 mb-1" for="admin-club-picker">Club</label>
        <af-select
          id="admin-club-picker"
          data-testid="admin-club-picker"
          [options]="clubOptions()"
          [value]="selectedClubId()"
          (valueChange)="onClubPicked($event)"
          [placeholder]="clubsLoading() ? 'Loading clubs…' : 'Pick a club to view its Locations'"
          [disabled]="clubsLoading()"
          [allowClear]="true"
        />
      </div>

      <af-page-error
        [message]="clubsError()"
        (retry)="reloadClubs()"
        data-testid="admin-clubs-error"
      />

      @if (selectedClubId()) {
        <af-page-error
          [message]="locationsError()"
          (retry)="reloadLocations()"
          data-testid="admin-locations-error"
        />

        <af-data-table
          data-testid="admin-locations-table"
          [items]="locations()"
          [loading]="locationsLoading()"
        >
          <ng-template #primary let-loc>
            <span class="text-slate-900 font-medium" [attr.data-testid]="'admin-location-row-' + loc.id">
              {{ loc.locationName }}
            </span>
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
            <af-button
              type="default"
              [danger]="true"
              htmlType="button"
              [attr.data-testid]="'admin-location-delete-' + loc.id"
              (clicked)="confirmDelete(loc)"
            >
              Delete
            </af-button>
          </ng-template>
        </af-data-table>
      }
    </af-page>
  `,
})
export class AdminLocationsPage {
  private readonly clubsApi = inject(ClubsService);
  private readonly adminApi = inject(LocationsAdminService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly clubs = signal<readonly ClubResponse[]>([]);
  protected readonly clubsLoading = signal(false);
  protected readonly clubsError = signal<string | null>(null);
  protected readonly locations = signal<readonly LocationListItem[]>([]);
  protected readonly locationsLoading = signal(false);
  protected readonly locationsError = signal<string | null>(null);

  private readonly queryParams = toSignal(this.route.queryParamMap, { requireSync: true });
  protected readonly selectedClubId = computed(() => this.queryParams().get('clubId'));

  protected readonly clubOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.clubs().map((c) => ({
      value: c.id ?? '',
      label: c.name ?? c.clubKey ?? (c.id ?? ''),
    })),
  );

  constructor() {
    this.reloadClubs();
    // Initial fetch when the route lands with ?clubId=… in the URL.
    const initial = this.selectedClubId();
    if (initial) {
      this.fetchLocations(initial);
    }
  }

  protected reloadClubs(): void {
    this.clubsLoading.set(true);
    this.clubsError.set(null);
    this.clubsApi.listClubs().subscribe({
      next: (items) => {
        this.clubs.set(items);
        this.clubsLoading.set(false);
      },
      error: () => {
        this.clubsLoading.set(false);
        this.clubsError.set('Failed to load clubs.');
      },
    });
  }

  protected onClubPicked(clubId: string | null): void {
    // Push the selection into the URL so a reload / shared link reproduces
    // the view. The constructor + an inline subscription mirror the read back.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { clubId: clubId ?? null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    this.locations.set([]);
    this.locationsError.set(null);
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

  protected confirmDelete(loc: LocationListItem): void {
    const clubId = this.selectedClubId();
    if (!clubId || typeof window === 'undefined') return;
    if (!window.confirm(`Delete "${loc.locationName}" from this club? This cannot be undone.`)) {
      return;
    }
    this.adminApi.adminDeleteLocation(clubId, loc.id).subscribe({
      next: () => this.fetchLocations(clubId),
      error: () => this.locationsError.set('Failed to delete the Location.'),
    });
  }

  private fetchLocations(clubId: string): void {
    this.locationsLoading.set(true);
    this.locationsError.set(null);
    this.adminApi.adminListLocations(clubId).subscribe({
      next: (items) => {
        this.locations.set(items);
        this.locationsLoading.set(false);
      },
      error: () => {
        this.locationsLoading.set(false);
        this.locationsError.set('Failed to load Locations for the selected club.');
      },
    });
  }
}
