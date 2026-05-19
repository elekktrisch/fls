import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';

import { ClubsStore } from '../clubs.store';

@Component({
  selector: 'af-clubs-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfDataTableComponent, RouterLink],
  template: `
    <header class="af-clubs-header">
      <h1>Clubs</h1>
      <af-button type="primary" htmlType="button" (clicked)="router.navigateByUrl('/clubs/new')">
        New club
      </af-button>
    </header>

    @if (store.loadError(); as err) {
      <p class="af-clubs-error" data-testid="clubs-error">Failed to load clubs: {{ err }}</p>
    }

    <af-data-table
      data-testid="clubs-table"
      [items]="store.entities()"
      [loading]="store.isLoading()"
    >
      <ng-template #primary let-club>
        <a [routerLink]="['/clubs', club.id, 'edit']" [attr.data-testid]="'club-row-' + club.slug">
          {{ club.name }}
        </a>
      </ng-template>
      <ng-template #secondary let-club>{{ club.slug }} · {{ club.clubKey }}</ng-template>
      <ng-template #meta let-club>
        @if (club.publicRegistrationEnabled) {
          <span class="af-clubs-badge">Public registration</span>
        }
      </ng-template>
    </af-data-table>
  `,
  styles: [
    `
      :host {
        display: block;
        padding: var(--space-row);
      }
      .af-clubs-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 1rem;
      }
      .af-clubs-error {
        color: var(--ant-error-color);
      }
      .af-clubs-badge {
        font-size: 0.75rem;
        padding: 0.125rem 0.5rem;
        border-radius: 999px;
        background: var(--color-brand-50, #eff6ff);
        color: var(--color-brand-700, #1d4ed8);
      }
    `,
  ],
})
export class ClubsListPage {
  protected readonly store = inject(ClubsStore);
  protected readonly router = inject(Router);
}
