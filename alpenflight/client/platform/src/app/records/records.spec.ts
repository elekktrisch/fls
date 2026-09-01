import { TestBed } from '@angular/core/testing';
import { provideRouter, type Routes } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes as destinationRoutes } from '../app.routes';
import { Shell } from '../shell/shell';

describe('Records', () => {
  beforeEach(() => {
    // Same RouterTestingHarness pattern as shell.spec.ts: nest the real route table under a
    // synthetic parent that activates Shell, so /records resolves exactly as it does in the app.
    const testRoutes: Routes = [{ path: '', component: Shell, children: destinationRoutes }];
    TestBed.configureTestingModule({ providers: [provideRouter(testRoutes)] });
  });

  it('rendersTheDemoFlightsGroupedByDateAtTheRecordsRoute', async () => {
    const harness = await RouterTestingHarness.create('/records');

    expect(harness.routeNativeElement!.textContent).toContain('HB-3215');
    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(7);
    expect(harness.routeNativeElement!.querySelectorAll('app-list-group-header').length).toBe(2);
  });

  it('narrowsTheListAsTheOperatorTypesAndShowsNoMatchForAnUnmatchedQuery', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const input = harness.routeNativeElement!.querySelector('input') as HTMLInputElement;
    input.value = 'HB-EAB';
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(1);
    expect(harness.routeNativeElement!.textContent).toContain('HB-EAB');

    input.value = 'no such registration';
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(0);
    expect(harness.routeNativeElement!.textContent).toContain('No record matches.');
  });

  it('matchesALowercaseQueryAgainstMixedCaseData', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const input = harness.routeNativeElement!.querySelector('input') as HTMLInputElement;
    input.value = 'hb-eab';
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(1);
    expect(harness.routeNativeElement!.textContent).toContain('HB-EAB');
  });

  it('narrowsOnAnActiveChipAndReturnsToThePriorFilteredStateWhenCleared', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const findChipButton = (text: string) =>
      Array.from(harness.routeNativeElement!.querySelectorAll('app-filter-chip button')).find(
        (button) => button.textContent?.trim() === text,
      ) as HTMLButtonElement;

    findChipButton('Settled').click();
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(3);

    const clearButton = Array.from(
      harness.routeNativeElement!.querySelectorAll('app-filter-chip button'),
    ).find((button) => button.getAttribute('aria-label') === 'Clear Settled') as HTMLButtonElement;
    clearButton.click();
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(7);
  });

  it('returnsToTheQueryFilteredStateNotTheFullListWhenAChipClearsWithASearchStillActive', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const input = harness.routeNativeElement!.querySelector('input') as HTMLInputElement;
    input.value = 'LSTO';
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(2);

    const findChipButton = (text: string) =>
      Array.from(harness.routeNativeElement!.querySelectorAll('app-filter-chip button')).find(
        (button) => button.textContent?.trim() === text,
      ) as HTMLButtonElement;

    findChipButton('Settled').click();
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(1);
    expect(harness.routeNativeElement!.textContent).toContain('HB-EAB');

    const clearButton = Array.from(
      harness.routeNativeElement!.querySelectorAll('app-filter-chip button'),
    ).find((button) => button.getAttribute('aria-label') === 'Clear Settled') as HTMLButtonElement;
    clearButton.click();
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelectorAll('app-record-item').length).toBe(2);
  });

  it('rendersOneFlatDurationOrderedListWithTheAbsentDurationRecordAlwaysLast', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const durationSortButton = Array.from(
      harness.routeNativeElement!.querySelectorAll('app-sort-control button'),
    ).find((button) => button.textContent?.trim().startsWith('Duration')) as HTMLButtonElement;

    durationSortButton.click(); // switches sort to duration, ascending
    harness.detectChanges();

    // Grouping is date-based; sorting by a field other than date must not keep stale date
    // group headers -- the list renders flat instead.
    expect(harness.routeNativeElement!.querySelectorAll('app-list-group-header').length).toBe(0);

    const identitiesAsc = Array.from(
      harness.routeNativeElement!.querySelectorAll('app-record-item .identity'),
    ).map((el) => el.textContent!.trim());
    // Ascending by duration, nulls last: f4(35) f1(42) f7(48) f5(52) f6(65) f3(78) f2(null).
    expect(identitiesAsc).toEqual([
      'HB-3215',
      'HB-3215',
      'HB-EAB',
      'HB-2101',
      'HB-1944',
      'HB-1944',
      'HB-2101',
    ]);
    expect(
      harness
        .routeNativeElement!.querySelector('app-record-item:last-child .metric')!
        .textContent!.trim(),
    ).toBe('not set');

    durationSortButton.click(); // flips to descending
    harness.detectChanges();

    // The absent-duration record must stay last on both directions -- descending must not invert
    // its placement the way a naive `direction * comparator` would.
    expect(
      harness
        .routeNativeElement!.querySelector('app-record-item:last-child .metric')!
        .textContent!.trim(),
    ).toBe('not set');
  });

  it('rendersNotSetForTheOpenFlightsAbsentMetric', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const openItem = Array.from(
      harness.routeNativeElement!.querySelectorAll('app-record-item'),
    ).find((item) => item.textContent?.includes('OPEN'))!;

    expect(openItem.querySelector('.metric')!.textContent!.trim()).toBe('not set');
  });

  it('colorsASettledRecordInkSettled', async () => {
    const harness = await RouterTestingHarness.create('/records');

    const billedItem = Array.from(
      harness.routeNativeElement!.querySelectorAll('app-record-item'),
    ).find((item) => item.textContent?.includes('BILLED'))!;

    expect(billedItem.classList.contains('record-item--settled')).toBe(true);
  });
});
