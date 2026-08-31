import { ChangeDetectionStrategy, Component } from '@angular/core';
import { SystemStatusCard } from '../../../../features/system-status/system-status-card';

@Component({
  selector: 'app-home',
  imports: [SystemStatusCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.html',
})
export class Home {
}
