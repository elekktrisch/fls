import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ListToolbar, type ToolbarChip } from './list-toolbar';
import type { SortField, SortState } from '../sort-control/sort-control';

// A small host exercises the two-way bindings the way records.ts (the real consumer) does — the
// only way to prove a value written inside ListToolbar actually reaches outside it.
@Component({
  selector: 'app-toolbar-test-host',
  imports: [ListToolbar],
  template: `
    <app-list-toolbar
      [chips]="chips"
      [sortFields]="sortFields"
      [(query)]="query"
      [(activeChipKeys)]="activeChipKeys"
      [(sort)]="sort"
    />
  `,
})
class ToolbarTestHost {
  readonly chips: readonly ToolbarChip[] = [
    { key: 'airborne', label: 'Airborne' },
    { key: 'settled', label: 'Settled' },
  ];
  readonly sortFields: readonly SortField[] = [
    { key: 'date', label: 'Date' },
    { key: 'duration', label: 'Duration' },
  ];
  readonly query = signal('');
  readonly activeChipKeys = signal<ReadonlySet<string>>(new Set());
  readonly sort = signal<SortState>({ key: 'date', direction: 'desc' });
}

describe('ListToolbar', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ToolbarTestHost] }).compileComponents();
  });

  it('rendersTheToolbarControlsInTheFixedOrderSearchThenChipsThenSort', () => {
    const fixture = TestBed.createComponent(ToolbarTestHost);
    fixture.detectChanges();

    const toolbar = fixture.nativeElement.querySelector('.toolbar');
    const childTags = Array.from(toolbar.children as HTMLElement[]).map((el) =>
      el.tagName.toLowerCase(),
    );
    expect(childTags).toEqual(['app-search-field', 'div', 'app-sort-control']);
  });

  it('propagatesTheSearchFieldsValueUpToTheHostAsTheOperatorTypes', () => {
    const fixture = TestBed.createComponent(ToolbarTestHost);
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = 'HB-3215';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.componentInstance.query()).toBe('HB-3215');
  });

  it('activatesAChipOnClickShowsItsClearControlAndClearsOnClear', () => {
    const fixture = TestBed.createComponent(ToolbarTestHost);
    fixture.detectChanges();

    const chipButtons = () =>
      fixture.nativeElement.querySelectorAll(
        'app-filter-chip button',
      ) as NodeListOf<HTMLButtonElement>;

    chipButtons()[0].click(); // Airborne's toggle
    fixture.detectChanges();

    expect(fixture.componentInstance.activeChipKeys().has('airborne')).toBe(true);
    expect(chipButtons().length).toBe(3); // Airborne toggle + Airborne clear + Settled toggle
    expect(chipButtons()[0].getAttribute('aria-pressed')).toBe('true');

    chipButtons()[1].click(); // Airborne's clear control
    fixture.detectChanges();

    expect(fixture.componentInstance.activeChipKeys().has('airborne')).toBe(false);
    expect(chipButtons().length).toBe(2);
  });

  it('cyclesTheSortFieldOnClickAndFlipsDirectionOnASecondClickOfTheActiveField', () => {
    const fixture = TestBed.createComponent(ToolbarTestHost);
    fixture.detectChanges();

    const sortButtons = () =>
      fixture.nativeElement.querySelectorAll(
        'app-sort-control button',
      ) as NodeListOf<HTMLButtonElement>;

    sortButtons()[1].click(); // switch from the default 'date' to 'duration'
    fixture.detectChanges();
    expect(fixture.componentInstance.sort()).toEqual({ key: 'duration', direction: 'asc' });

    sortButtons()[1].click(); // click the now-active field again
    fixture.detectChanges();
    expect(fixture.componentInstance.sort()).toEqual({ key: 'duration', direction: 'desc' });
  });
});
