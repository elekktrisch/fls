import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { HelloComponent } from './hello.component';

describe('HelloComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HelloComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the greeting fetched via the generated client', async () => {
    const fixture = TestBed.createComponent(HelloComponent);
    fixture.detectChanges();

    const httpTesting = TestBed.inject(HttpTestingController);
    const req = httpTesting.expectOne('/api/v1/hello');
    expect(req.request.method).toBe('GET');
    req.flush({ message: 'Hello AlpenFlight', timestamp: '2026-01-01T00:00:00Z' });

    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toMatch(/Hello AlpenFlight/);
    expect(text).toMatch(/2026/);

    httpTesting.verify();
  });
});
