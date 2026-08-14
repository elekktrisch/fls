import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'af-wordmark',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <img
      class="hidden md:block h-6 w-auto"
      src="brand/wordmark-full.svg"
      [alt]="label()"
      data-testid="af-wordmark-full"
    />
    <img
      class="block md:hidden h-8 w-auto"
      src="brand/wordmark-compact.svg"
      [alt]="label()"
      data-testid="af-wordmark-compact"
    />
  `,
})
export class AfWordmarkComponent {
  readonly label = input<string>('AlpenFlight');
}
