import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfIconComponent } from '@ui/atoms/af-icon';

// Resolves so the landing `Try demo` CTA deep-links without a catch-all
// redirect; the interactive sandbox is J-20.
@Component({
  selector: 'af-demo-stub',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfIconComponent, RouterLink, TranslocoDirective],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t; read: 'publicStub'">
      <main
        class="min-h-screen flex items-center justify-center px-4 py-12 bg-white"
        data-testid="demo-stub"
      >
        <section class="w-full max-w-md flex flex-col items-center gap-4 text-center">
          <p class="m-0 text-sm font-medium text-slate-500">
            {{ t('demo') }}
          </p>
          <h1 class="m-0 text-2xl font-medium tracking-tight text-slate-900">
            {{ t('title') }}
          </h1>
          <p class="m-0 text-base leading-normal text-slate-500 max-w-prose">
            {{ t('demoBody') }}
          </p>
          <a
            routerLink="/"
            class="mt-2 inline-flex items-center gap-2 text-brand-700 hover:text-brand-500 no-underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
            data-testid="public-stub-back"
          >
            <af-icon name="arrow-left" [size]="16" />
            <span>{{ t('back') }}</span>
          </a>
        </section>
      </main>
    </ng-container>
  `,
})
export class DemoStubComponent {}
