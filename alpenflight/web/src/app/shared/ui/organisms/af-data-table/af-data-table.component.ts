import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  contentChild,
  input,
  output,
  type TemplateRef,
} from '@angular/core';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzSpinModule } from 'ng-zorro-antd/spin';

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
 * Below md the row stacks vertically (card-like); at md and above the
 * cells flow horizontally — primary takes remaining width, secondary
 * sits next to it, meta is pinned to the right. The kebab / trailing
 * action lives in #meta, which is why meta is the flex-none rightmost
 * cell, not the wrapping one.
 *
 * The `[virtualScroll]` seam is wired but no-op — S-047 turns it on for
 * lists >500 rows.
 */
@Component({
  selector: 'af-data-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, NzEmptyModule, NzPaginationModule, NzSpinModule],
  host: { class: 'block' },
  template: `
    @if (loading()) {
      <div class="flex justify-center py-12">
        <nz-spin />
      </div>
    } @else if (items().length === 0) {
      <nz-empty />
    } @else {
      <ul role="list" class="flex flex-col gap-2 list-none m-0 p-0">
        @for (item of items(); track item) {
          <li
            class="flex items-center gap-4 p-4 bg-white border border-slate-200 hover:border-slate-300"
          >
            <div class="flex-1 min-w-0 flex flex-col gap-0.5">
              <div class="font-medium truncate">
                @if (primary(); as tpl) {
                  <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
                }
              </div>
              @if (secondary(); as tpl) {
                <div class="text-sm text-slate-500 truncate">
                  <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
                </div>
              }
            </div>
            @if (meta(); as tpl) {
              <div class="flex-none">
                <ng-container *ngTemplateOutlet="tpl; context: { $implicit: item }" />
              </div>
            }
          </li>
        }
      </ul>
      @if (showPagination()) {
        <nz-pagination
          class="block mt-3"
          [nzPageSize]="pageSize()"
          [nzTotal]="total() ?? items().length"
          (nzPageIndexChange)="onPageIndexChange($event)"
        />
      }
    }
  `,
})
export class AfDataTableComponent<T> {
  readonly items = input.required<readonly T[]>();
  readonly pageSize = input<number>(20);
  readonly total = input<number | null>(null);
  readonly loading = input<boolean>(false);
  readonly virtualScroll = input<boolean>(false);
  readonly showPagination = input<boolean>(false);

  readonly primary = contentChild<TemplateRef<{ $implicit: T }>>('primary');
  readonly secondary = contentChild<TemplateRef<{ $implicit: T }>>('secondary');
  readonly meta = contentChild<TemplateRef<{ $implicit: T }>>('meta');

  readonly pageChange = output<PageChange>();

  protected onPageIndexChange(page: number): void {
    this.pageChange.emit({ page, pageSize: this.pageSize() });
  }
}
