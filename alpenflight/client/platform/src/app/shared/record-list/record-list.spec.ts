import { TestBed } from '@angular/core/testing';
import { RecordList } from './record-list';
import type { RecordItemData } from '../record-item/record-item';

describe('RecordList', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RecordList] }).compileComponents();
  });

  function record(id: string): RecordItemData {
    return { id, identity: id, meta: [], metric: null };
  }

  it('rendersOneRecordItemPerRecordAndNoGroupHeadersWhenUngrouped', () => {
    const fixture = TestBed.createComponent(RecordList);
    fixture.componentRef.setInput('items', [record('a'), record('b')]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('app-record-item').length).toBe(2);
    expect(fixture.nativeElement.querySelectorAll('app-list-group-header').length).toBe(0);
  });

  it('rendersANoMatchMessageWhenItemsIsEmpty', () => {
    const fixture = TestBed.createComponent(RecordList);
    fixture.componentRef.setInput('items', []);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No record matches.');
  });

  it('groupsRecordsInFirstAppearanceOrderAndNamesEachGroupWithItsCount', () => {
    const fixture = TestBed.createComponent(RecordList);
    fixture.componentRef.setInput('items', [record('a'), record('b'), record('c')]);
    fixture.componentRef.setInput('groupBy', (r: RecordItemData) => (r.id === 'c' ? 'B' : 'A'));
    fixture.detectChanges();

    const headers = fixture.nativeElement.querySelectorAll('app-list-group-header');
    expect(headers.length).toBe(2);
    expect(headers[0].textContent).toContain('A · 2');
    expect(headers[1].textContent).toContain('B · 1');
    expect(fixture.nativeElement.querySelectorAll('app-record-item').length).toBe(3);
  });
});
