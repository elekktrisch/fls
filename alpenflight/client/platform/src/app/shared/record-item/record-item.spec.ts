import { TestBed } from '@angular/core/testing';
import { RecordItem, type RecordItemData } from './record-item';

describe('RecordItem', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RecordItem] }).compileComponents();
  });

  function createRecord(overrides: Partial<RecordItemData> = {}): RecordItemData {
    return {
      id: 'r1',
      identity: 'HB-3215',
      meta: ['S. AEBI', 'OFF 10:24'],
      metric: '01:18',
      ...overrides,
    };
  }

  it('joinsMetaPartsWithAMiddleDot', () => {
    const fixture = TestBed.createComponent(RecordItem);
    fixture.componentRef.setInput('record', createRecord());
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.meta').textContent).toBe('S. AEBI · OFF 10:24');
  });

  it('rendersNotSetForAnAbsentMetricAndKeepsTheMetricZone', () => {
    const fixture = TestBed.createComponent(RecordItem);
    fixture.componentRef.setInput('record', createRecord({ metric: null }));
    fixture.detectChanges();

    const metric = fixture.nativeElement.querySelector('.metric');
    expect(metric.textContent.trim()).toBe('not set');
    expect(metric.classList.contains('metric--absent')).toBe(true);
  });

  it('rendersALiveMetricInLiveColorWhenNotSettled', () => {
    const fixture = TestBed.createComponent(RecordItem);
    fixture.componentRef.setInput('record', createRecord({ metricLive: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.metric').classList.contains('metric--live')).toBe(
      true,
    );
  });

  it('colorsEveryZoneInkSettledForASettledRecordAndSuppressesTheLiveMetricColor', () => {
    const fixture = TestBed.createComponent(RecordItem);
    fixture.componentRef.setInput('record', createRecord({ settled: true, metricLive: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.classList.contains('record-item--settled')).toBe(true);
    expect(fixture.nativeElement.querySelector('.metric').classList.contains('metric--live')).toBe(
      false,
    );
  });

  it('rendersTheMarkerWithItsToneWhenPresentAndOmitsItWhenAbsent', () => {
    const fixture = TestBed.createComponent(RecordItem);
    fixture.componentRef.setInput(
      'record',
      createRecord({ marker: { label: 'AIRBORNE', tone: 'airborne' } }),
    );
    fixture.detectChanges();

    const marker = fixture.nativeElement.querySelector('.marker');
    expect(marker.textContent.trim()).toBe('AIRBORNE');
    expect(marker.classList.contains('marker--airborne')).toBe(true);

    fixture.componentRef.setInput('record', createRecord({ marker: undefined }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.marker')).toBeNull();
  });
});
