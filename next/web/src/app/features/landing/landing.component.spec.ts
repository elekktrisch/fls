import { TestBed } from '@angular/core/testing';
import { LandingComponent } from './landing.component';

describe('LandingComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingComponent],
    }).compileComponents();
  });

  it('renders hello message', () => {
    const fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
    const heading = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(heading?.textContent).toMatch(/Hello FLS/i);
  });

  it('applies tailwind utility class', () => {
    const fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
    const heading = fixture.nativeElement.querySelector('h1') as HTMLElement;
    expect(heading.classList.contains('text-blue-600')).toBe(true);
  });
});
