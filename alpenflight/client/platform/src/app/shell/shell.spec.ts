import { TestBed } from '@angular/core/testing';
import { provideRouter, type Routes } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes as destinationRoutes } from '../app.routes';
import { Shell } from './shell';

describe('Shell', () => {
  beforeEach(() => {
    // Shell is not itself routed in the real app — App mounts it directly, and it hosts the
    // <router-outlet> the destination routes activate inside. Nesting the real app.routes.ts
    // array as children under a synthetic parent route that activates Shell reproduces exactly
    // that structure for RouterTestingHarness, while proving the nav against the same route table
    // the app ships.
    const testRoutes: Routes = [{ path: '', component: Shell, children: destinationRoutes }];
    TestBed.configureTestingModule({
      providers: [provideRouter(testRoutes)],
    });
  });

  it('rendersOneNavLinkPerDestinationFromTheRouteTable', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const links = harness.routeNativeElement!.querySelectorAll('a.destination');
    const hrefs = Array.from(links).map((link) => (link as HTMLAnchorElement).getAttribute('href'));

    expect(hrefs).toEqual(['/operate', '/plan', '/records', '/admin']);
  });

  it('marksTheActiveDestinationWithBothTextColorAndRuleViaTheRouterLinkActiveClass', async () => {
    const harness = await RouterTestingHarness.create('/plan');

    const activeLinks = harness.routeNativeElement!.querySelectorAll('a.destination--active');
    expect(activeLinks.length).toBe(1);
    expect((activeLinks[0] as HTMLAnchorElement).getAttribute('href')).toBe('/plan');

    const inactiveLinks = harness.routeNativeElement!.querySelectorAll(
      'a.destination:not(.destination--active)',
    );
    expect(inactiveLinks.length).toBe(3);
  });

  it('rendersTheRoutedDestinationsLabelInTheRouterOutlet', async () => {
    const harness = await RouterTestingHarness.create('/plan');

    expect(harness.routeNativeElement!.textContent).toContain('Plan');
  });

  it('rendersHomeContentAtTheRootPath', async () => {
    const harness = await RouterTestingHarness.create('/');

    expect(harness.routeNativeElement!.textContent).toContain('AlpenFlight');
  });
});
