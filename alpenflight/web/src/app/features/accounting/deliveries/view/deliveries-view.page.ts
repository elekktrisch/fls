import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import type { DeliveryRecipientView } from '@api/generated/model';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

import { processStateClass, processStateKey } from '../process-state';
import { DeliveriesStore } from '../deliveries.store';

interface RecipientFieldSpec {
  readonly key: keyof DeliveryRecipientView;
  // Full literal so the i18n key-coverage gate resolves it (no concatenation).
  readonly labelKey: string;
}

// The nine frozen recipient-snapshot fields (OR Art. 957a), rendered in a fixed
// order; each maps to a `del-recipient-<field>` testid.
const RECIPIENT_FIELDS: readonly RecipientFieldSpec[] = [
  { key: 'name', labelKey: 'view.recipientFields.name' },
  { key: 'firstName', labelKey: 'view.recipientFields.firstName' },
  { key: 'lastName', labelKey: 'view.recipientFields.lastName' },
  { key: 'clubMemberNumber', labelKey: 'view.recipientFields.clubMemberNumber' },
  { key: 'addressLine1', labelKey: 'view.recipientFields.addressLine1' },
  { key: 'addressLine2', labelKey: 'view.recipientFields.addressLine2' },
  { key: 'zipCode', labelKey: 'view.recipientFields.zipCode' },
  { key: 'city', labelKey: 'view.recipientFields.city' },
  { key: 'country', labelKey: 'view.recipientFields.country' },
];

interface RecipientField extends RecipientFieldSpec {
  readonly value: string;
}

/**
 * Delivery view (`/deliveries/:id`). READ-ONLY: the engine's line items, the
 * frozen recipient snapshot, the linked flight, and the state badge. A 404 is
 * the tenant-isolation outcome — the @TenantId finder never returns another
 * club's row — so a cross-tenant id surfaces `del-not-found` instead of detail.
 */
@Component({
  selector: 'af-deliveries-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfPageComponent, AfPageHeaderComponent, RouterLink, TranslocoDirective],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'deliveries'">
        @if (store.notFound()) {
          <p class="text-slate-600" data-testid="del-not-found">{{ t('view.notFound') }}</p>
        } @else if (store.selectedDetail(); as d) {
          <div class="flex flex-col gap-6" data-testid="del-detail">
            <af-page-header
              [title]="(d.deliveryNumber ?? t('list.unbooked')) + ' · ' + d.recipient.lastName"
            >
              <span
                class="inline-block px-2 py-0.5 text-xs border"
                [class]="stateClass(d.processStateId)"
                data-testid="del-state-badge"
              >
                {{ t(stateKey(d.processStateId)) }}
              </span>
            </af-page-header>

            <section class="flex flex-col gap-2">
              <h2 class="text-sm font-medium text-slate-500 m-0">{{ t('view.items') }}</h2>
              <ul role="list" class="flex flex-col gap-1 list-none m-0 p-0">
                @for (item of d.items; track item.position) {
                  <li
                    class="flex items-center gap-4 p-3 bg-white border border-slate-200 text-sm"
                    [attr.data-testid]="'del-item-' + $index"
                  >
                    <span class="tabular text-slate-500 w-8">{{ item.position }}</span>
                    <span class="tabular font-medium w-24">{{ item.articleNumber }}</span>
                    <span class="flex-1 min-w-0 truncate">{{ item.itemText }}</span>
                    <span class="tabular">{{ item.quantity }}</span>
                    <span class="text-slate-500 w-16">{{ item.unitType }}</span>
                  </li>
                }
              </ul>
            </section>

            <section class="flex flex-col gap-2">
              <h2 class="text-sm font-medium text-slate-500 m-0">{{ t('view.recipient') }}</h2>
              <dl class="grid grid-cols-2 gap-x-6 gap-y-1 text-sm m-0">
                @for (field of recipientFields(); track field.key) {
                  <div class="contents">
                    <dt class="text-slate-500">{{ t(field.labelKey) }}</dt>
                    <dd class="m-0" [attr.data-testid]="'del-recipient-' + field.key">
                      {{ field.value }}
                    </dd>
                  </div>
                }
              </dl>
            </section>

            @if (d.flight; as flight) {
              <section class="flex flex-col gap-2">
                <h2 class="text-sm font-medium text-slate-500 m-0">{{ t('view.flight') }}</h2>
                <a
                  class="text-brand-700 no-underline hover:underline tabular"
                  [routerLink]="['/flights', flight.flightId]"
                  data-testid="del-flight-link"
                >
                  {{ flight.flightId }}
                </a>
              </section>
            }
          </div>
        } @else {
          <p class="text-slate-500" data-testid="del-loading">{{ t('view.loading') }}</p>
        }
      </ng-container>
    </af-page>
  `,
})
export class DeliveriesViewPage {
  protected readonly store = inject(DeliveriesStore);
  private readonly route = inject(ActivatedRoute);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly deliveryId = computed(() => this.routeId().get('id'));

  protected readonly recipientFields = computed<RecipientField[]>(() => {
    const recipient = this.store.selectedDetail()?.recipient;
    if (!recipient) return [];
    return RECIPIENT_FIELDS.filter((f) => (recipient[f.key] ?? '') !== '').map((f) => ({
      ...f,
      value: recipient[f.key] as string,
    }));
  });

  constructor() {
    effect(() => {
      const id = this.deliveryId();
      if (id) this.store.loadDetail(id);
    });
  }

  protected stateKey(processStateId: number): string {
    return processStateKey(processStateId);
  }

  protected stateClass(processStateId: number): string {
    return processStateClass(processStateId);
  }
}
