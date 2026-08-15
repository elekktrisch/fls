import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

@Component({
  selector: 'af-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `<div [class]="containerClasses()"><ng-content /></div>`,
})
export class AfPageComponent {
  readonly mode = input<'wide' | 'narrow'>('wide');

  protected readonly containerClasses = computed(
    () =>
      `w-full mx-auto px-4 py-6 md:px-6 lg:px-8 xl:px-12 ${this.mode() === 'narrow' ? 'max-w-[40rem]' : ''}`,
  );
}
