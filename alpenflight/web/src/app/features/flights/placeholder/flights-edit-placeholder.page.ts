import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map, startWith } from 'rxjs';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

/**
 * Stand-in landing for `/flights/new`, `/flights/:id/edit`, `/flights/copy/:id`.
 * Replaced by the real edit / create / copy pages when S-062c lands. Keeps a
 * thin reviewer-sanity surface so the navigation entry points wire and assert
 * end-to-end before the form mechanics ship.
 */
@Component({
  selector: 'af-flights-edit-placeholder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AfPageComponent, AfPageHeaderComponent],
  template: `
    <af-page>
      <af-page-header [title]="title()" />
      <p data-testid="flights-edit-placeholder" class="text-slate-600">
        Flight {{ mode() }} forms land in S-062c.
      </p>
      <p>
        <a class="text-brand-600 hover:text-brand-700" routerLink="/flights">Back to flight list</a>
      </p>
    </af-page>
  `,
})
export class FlightsEditPlaceholderPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly mode = toSignal(
    this.route.url.pipe(
      map((segments) => {
        const head = segments[0]?.path;
        if (head === 'new') return 'new';
        if (head === 'copy') return 'copy';
        return 'edit';
      }),
      startWith<'new' | 'copy' | 'edit'>('edit'),
    ),
    { initialValue: 'edit' as const },
  );

  protected readonly title = computed(() => {
    switch (this.mode()) {
      case 'new':
        return 'New flight';
      case 'copy':
        return 'Copy flight';
      case 'edit':
      default:
        return 'Edit flight';
    }
  });
}
