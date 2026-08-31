import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { DESTINATIONS } from '../app.routes';

@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.html',
  styleUrl: './shell.css',
})
export class Shell {
  // app.routes.ts owns the destination path/label list; the nav link needs an absolute path.
  protected readonly destinations = DESTINATIONS.map((destination) => ({
    path: `/${destination.path}`,
    label: destination.label,
  }));
}
