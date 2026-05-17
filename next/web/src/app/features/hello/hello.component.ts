import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { HelloStore } from './hello.store';

// TODO(S-020): remove or auth-gate before cutover.
@Component({
  selector: 'af-hello',
  imports: [DatePipe],
  template: `
    @if (store.isLoading()) {
      <p>Loading…</p>
    } @else if (store.hasError()) {
      <p class="text-red-600">Failed: {{ store.loadError() }}</p>
    } @else if (store.offline()) {
      <p>Offline — last refreshed {{ store.lastRefreshedAt() | date: 'short' }}</p>
    } @else if (firstItem(); as r) {
      <h1 class="text-blue-600 text-3xl font-bold">{{ r.message }}</h1>
      <p>{{ r.timestamp | date: 'medium' }}</p>
    }
    @if (store.showAdvanced()) {
      <p class="text-sm text-gray-500">Advanced view</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HelloComponent {
  protected readonly store = inject(HelloStore);
  protected readonly firstItem = computed(() => this.store.items()[0] ?? null);
}
