import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  TemplateRef,
  contentChild,
  inject,
  input,
  output,
} from '@angular/core';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzTableModule } from 'ng-zorro-antd/table';

import { ViewportService } from '../../viewport';

export interface DataTableColumn<T> {
  readonly key: keyof T & string;
  readonly label: string;
  readonly sortable?: boolean;
}

export type SortDirection = 'asc' | 'desc' | null;

export interface SortChange<T> {
  readonly key: keyof T & string;
  readonly direction: SortDirection;
}

export interface PageChange {
  readonly page: number;
  readonly pageSize: number;
}

/**
 * Row mode (>=md) renders `nz-table`. Card mode (<md) renders a stack of
 * `nz-card`s with consumer-provided `[primary]` / `[secondary]` / `[meta]`
 * slots. Mode auto-resolves from viewport unless `mode` is forced.
 *
 * The `[virtualScroll]` seam is wired but no-op — S-047 turns it on for
 * lists >500 rows.
 */
@Component({
  selector: 'af-data-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzTableModule, NzCardModule, NgTemplateOutlet],
  template: `
    @if (effectiveCardMode()) {
      <div class="af-card-list">
        @for (item of items(); track trackBy()(0, item)) {
          <nz-card>
            @if (primary(); as tpl) {
              <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
            }
            @if (secondary(); as tpl) {
              <div class="af-card-secondary">
                <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
              </div>
            }
            @if (meta(); as tpl) {
              <div class="af-card-meta">
                <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
              </div>
            }
          </nz-card>
        }
      </div>
    } @else {
      <nz-table
        [nzData]="asArray()"
        [nzShowPagination]="true"
        [nzPageSize]="pageSize()"
        [nzLoading]="loading()"
      >
        <thead>
          <tr>
            @for (col of columns(); track col.key) {
              <th>{{ col.label }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (item of items(); track trackBy()(0, item)) {
            <tr>
              @for (col of columns(); track col.key) {
                <td>{{ asRecord(item)[col.key] }}</td>
              }
            </tr>
          }
        </tbody>
      </nz-table>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .af-card-list {
        display: flex;
        flex-direction: column;
        gap: var(--space-row);
      }
      .af-card-secondary {
        font-size: 0.875rem;
        opacity: 0.85;
      }
      .af-card-meta {
        font-size: 0.75rem;
        opacity: 0.7;
      }
    `,
  ],
})
export class AfDataTableComponent<T> {
  readonly #viewport = inject(ViewportService);

  readonly items = input.required<readonly T[]>();
  readonly columns = input.required<readonly DataTableColumn<T>[]>();
  readonly mode = input<'row' | 'card' | 'auto'>('auto');
  readonly pageSize = input<number>(20);
  readonly loading = input<boolean>(false);
  readonly virtualScroll = input<boolean>(false);
  readonly trackBy = input<(_: number, item: T) => unknown>((_, item) => item);

  readonly primary = contentChild<TemplateRef<{ $implicit: T }>>('primary');
  readonly secondary = contentChild<TemplateRef<{ $implicit: T }>>('secondary');
  readonly meta = contentChild<TemplateRef<{ $implicit: T }>>('meta');

  readonly sortChange = output<SortChange<T>>();
  readonly pageChange = output<PageChange>();

  protected effectiveCardMode(): boolean {
    const m = this.mode();
    if (m === 'card') return true;
    if (m === 'row') return false;
    return this.#viewport.isBelow('md')();
  }

  protected asArray(): readonly T[] {
    return this.items();
  }

  protected asRecord(item: T): Record<string, unknown> {
    return item as unknown as Record<string, unknown>;
  }
}
