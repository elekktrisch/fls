import { TestBed } from '@angular/core/testing';

import { TranslocoBundledLoader } from './transloco-loader';

describe('TranslocoBundledLoader', () => {
  let loader: TranslocoBundledLoader;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    loader = TestBed.inject(TranslocoBundledLoader);
  });

  it('loads de translations from the bundled chunk', async () => {
    const t = await loader.getTranslation('de');
    expect(t['landing']).toEqual(
      expect.objectContaining({
        tagline: expect.stringContaining('Flugbuch'),
      }),
    );
  });

  it('loads each supported locale and returns a populated translation', async () => {
    for (const loc of ['de', 'fr', 'it', 'en'] as const) {
      const t = await loader.getTranslation(loc);
      expect(t['landing']).toBeDefined();
    }
  });

  it('returns an empty translation for an unknown locale', async () => {
    const t = await loader.getTranslation('zz');
    expect(t).toEqual({});
  });
});
