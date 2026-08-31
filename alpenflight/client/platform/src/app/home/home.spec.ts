import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Home } from './home';

describe('Home', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('shouldCreateHome', () => {
    const fixture = TestBed.createComponent(Home);

    fixture.detectChanges();

    expect(fixture.componentInstance).toBeTruthy();
    httpMock.expectOne('/api/v1/system/status').flush({ status: 'UP', serverTime: '2026-08-29T00:00:00Z' });
  });

  it('rendersTheSystemStatusCardsLoadingStateBeforeTheResponseArrives', () => {
    const fixture = TestBed.createComponent(Home);

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Checking system status.');
    httpMock.expectOne('/api/v1/system/status').flush({ status: 'UP', serverTime: '2026-08-29T00:00:00Z' });
  });

  it('rendersTheSystemStatusCardsValueOnceTheResponseArrives', async () => {
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/system/status').flush({ status: 'UP', serverTime: '2026-08-29T00:00:00Z' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('System status: UP');
    expect(fixture.nativeElement.textContent).toContain('Server time: 2026-08-29T00:00:00Z');
  });

  it('rendersTheSystemStatusCardsErrorStateWhenTheRequestFails', async () => {
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/v1/system/status')
      .flush('failure', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('The system status is not available.');
  });
});
