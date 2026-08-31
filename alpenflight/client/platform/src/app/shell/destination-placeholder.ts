import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

@Component({
  selector: 'app-destination-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './destination-placeholder.html',
})
export class DestinationPlaceholder {
  protected readonly route = inject(ActivatedRoute);
}
