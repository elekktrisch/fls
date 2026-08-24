import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { DemoSeatSession } from '@core/session/demo-seat.session';
import { AfIconComponent } from '@ui/atoms/af-icon';

import { emitFunnelEvent } from '../signup/funnel-telemetry';

const SIGNUP_INTENT_THE_BANNER_OPENS = 'migrate';

const SIGNUP_QUERY_PARAMS_OF_THE_MIGRATE_INTENT = { intent: SIGNUP_INTENT_THE_BANNER_OPENS };

@Component({
  selector: 'af-demo-seat-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfIconComponent, RouterLink, TranslocoDirective],
  host: { class: 'block sticky top-0 z-50' },
  template: `
    <ng-container *transloco="let t; read: 'demo'">
      @if (theVisitorHoldsADemoSeat()) {
        <div
          class="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-slate-200 bg-slate-50
            px-4 py-2 md:px-6 lg:px-8 xl:px-12"
          data-testid="demo-banner"
        >
          <span class="text-sm font-medium text-slate-900">{{ t('eyebrow') }}</span>
          <span class="text-sm text-slate-600">{{ t('banner.privacy') }}</span>
          <span class="text-sm text-slate-500">{{ t('banner.expiry') }}</span>
          <a
            routerLink="/signup"
            [queryParams]="signupQueryParams"
            class="ml-auto inline-flex items-center gap-2 py-1.5 text-sm font-medium text-brand-600
              no-underline hover:text-brand-700 focus-visible:outline focus-visible:outline-2
              focus-visible:outline-brand-500 focus-visible:outline-offset-2"
            data-testid="demo-banner-cta"
            (click)="recordTheBannerCallToAction()"
          >
            {{ t('banner.cta') }}
            <af-icon name="arrow-right" [size]="16" />
          </a>
        </div>
      }
    </ng-container>
  `,
})
export class DemoSeatBannerComponent {
  readonly #demoSeat = inject(DemoSeatSession);

  protected readonly theVisitorHoldsADemoSeat = this.#demoSeat.isLive;

  protected readonly signupQueryParams = SIGNUP_QUERY_PARAMS_OF_THE_MIGRATE_INTENT;

  protected recordTheBannerCallToAction(): void {
    emitFunnelEvent({
      event_id: 'demo.signup_cta_click',
      timestamp: new Date().toISOString(),
      properties: { intent: SIGNUP_INTENT_THE_BANNER_OPENS },
    });
  }
}
