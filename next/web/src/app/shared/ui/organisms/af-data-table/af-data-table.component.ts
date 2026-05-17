import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  TemplateRef,
  contentChild,
  input,
  output,
} from '@angular/core';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzSpinModule } from 'ng-zorro-antd/spin';

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
 * List-based data view. Renders `<ul role="list">` with `<li>` items —
 * never a `<table>`. The consumer projects three named templates:
 *
 *   <af-data-table [items]="...">
 *     <ng-template #primary let-item>{{ item.name }}</ng-template>
 *     <ng-template #secondary let-item>{{ item.subtitle }}</ng-template>
 *     <ng-template #meta let-item>{{ item.timestamp }}</ng-template>
 *   </af-data-table>
 *
 * Layout is fully CSS-responsive: items stack vertically inside each row at
 * narrow viewports (card-like), and flow horizontally with whitespace at
 * wider viewports. Density-scoped tokens (`--space-row`, `--row-height`)
 * drive spacing; no breakpoint JS.
 *
 * The `[virtualScroll]` seam is wired but no-op — S-047 turns it on for
 * lists >500 rows.
 */
@Component({
  selector: 'af-data-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, NzEmptyModule, NzPaginationModule, NzSpinModule],
  template: `
    @if (loading()) {
      <nz-spin />
    } @else if (items().length === 0) {
      <nz-empty />
    } @else {
      <ul role="list" class="af-list">
        @for (item of items(); track trackBy()(0, item)) {
          <li class="af-list-item">
            <div class="af-list-primary">
              @if (primary(); as tpl) {
                <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
              }
            </div>
            @if (secondary(); as tpl) {
              <div class="af-list-secondary">
                <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
              </div>
            }
            @if (meta(); as tpl) {
              <div class="af-list-meta">
                <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
              </div>
            }
          </li>
        }
      </ul>
      @if (showPagination()) {
        <nz-pagination
          class="af-list-pagination"
          [nzPageSize]="pageSize()"
          [nzTotal]="total() ?? items().length"
          (nzPageIndexChange)="onPageIndexChange($event)"
        />
      }
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .af-list {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: var(--space-row);
      }
      .af-list-item {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        padding: var(--space-field);
        border: 1px solid var(--ant-border-color-base, #f0f0f0);
        border-radius: var(--radius-md);
        background: #fff;
      }
      @container (min-width: 768px) {
        .af-list-item {
          flex-direction: row;
          align-items: center;
          gap: 1rem;
        }
        .af-list-primary {
          flex: 1 1 auto;
        }
        .af-list-secondary {
          flex: 0 1 auto;
          opacity: 0.85;
        }
        .af-list-meta {
          flex: 0 0 auto;
          opacity: 0.7;
          font-size: 0.875rem;
        }
      }
      .af-list-primary {
        font-weight: 500;
      }
      .af-list-secondary {
        font-size: 0.875rem;
        opacity: 0.85;
      }
      .af-list-meta {
        font-size: 0.75rem;
        opacity: 0.7;
      }
      .af-list-pagination {
        margin-top: 0.75rem;
        display: block;
      }
    `,
  ],
})
export class AfDataTableComponent<T> {
  readonly items = input.required<readonly T[]>();
  readonly pageSize = input<number>(20);
  readonly total = input<number | null>(null);
  readonly loading = input<boolean>(false);
  readonly virtualScroll = input<boolean>(false);
  readonly showPagination = input<boolean>(false);
  readonly trackBy = input<(_: number, item: T) => unknown>((_, item) => item);

  readonly primary = contentChild<TemplateRef<{ $implicit: T }>>('primary');
  readonly secondary = contentChild<TemplateRef<{ $implicit: T }>>('secondary');
  readonly meta = contentChild<TemplateRef<{ $implicit: T }>>('meta');

  readonly sortChange = output<SortChange<T>>();
  readonly pageChange = output<PageChange>();

  protected onPageIndexChange(page: number): void {
    this.pageChange.emit({ page, pageSize: this.pageSize() });
  }
}
