import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@api/generated/hello/hello.resource', () => ({
  helloResource: () => ({
    value: signal({ message: 'Hello AlpenFlight', timestamp: '2026-01-01T00:00:00Z' }),
    error: signal(undefined),
    isLoading: signal(false),
  }),
}));

import { HelloComponent } from './hello.component';

describe('HelloComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HelloComponent] }).compileComponents();
  });

  it('renders the greeting from the generated client', () => {
    const fixture = TestBed.createComponent(HelloComponent);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toMatch(/Hello AlpenFlight/);
    expect(text).toMatch(/2026/);
  });
});
