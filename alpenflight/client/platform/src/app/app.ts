import { Component } from '@angular/core';
import { SystemStatusCard } from '../../../features/system-status/system-status-card';

@Component({
  selector: 'app-root',
  imports: [SystemStatusCard],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
}
