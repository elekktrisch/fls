import { fuzzyFilter } from './fuzzy-filter';

interface Person {
  readonly id: string;
  readonly first: string;
  readonly last: string;
  readonly city: string;
}

const people: readonly Person[] = [
  { id: '1', first: 'Alice', last: 'Müller', city: 'Bern' },
  { id: '2', first: 'Bob', last: 'Schmidt', city: 'Zürich' },
  { id: '3', first: 'Charlie', last: 'Weber', city: 'Basel' },
];

describe('fuzzyFilter', () => {
  it('returns all items when query is empty', () => {
    expect(fuzzyFilter(people, ['first', 'last', 'city'], '')).toEqual(people);
    expect(fuzzyFilter(people, ['first', 'last', 'city'], '   ')).toEqual(people);
  });

  it('matches case-insensitively on any search field', () => {
    expect(fuzzyFilter(people, ['first', 'last', 'city'], 'mül')).toEqual([people[0]]);
    expect(fuzzyFilter(people, ['first', 'last', 'city'], 'ZÜR')).toEqual([people[1]]);
    expect(fuzzyFilter(people, ['first', 'last', 'city'], 'we')).toEqual([people[2]]);
  });

  it('returns empty list when query matches nothing', () => {
    expect(fuzzyFilter(people, ['first', 'last', 'city'], 'xyzzy')).toEqual([]);
  });

  it('honours the searchFields restriction (city not searched)', () => {
    expect(fuzzyFilter(people, ['first', 'last'], 'bern')).toEqual([]);
    expect(fuzzyFilter(people, ['city'], 'bern')).toEqual([people[0]]);
  });

  it('completes within budget at 200 items × 3 fields', () => {
    const big: Person[] = Array.from({ length: 200 }, (_, i) => ({
      id: String(i),
      first: `First${i}`,
      last: `Last${i}`,
      city: `City${i}`,
    }));
    const start = performance.now();
    fuzzyFilter(big, ['first', 'last', 'city'], 'last42');
    const elapsed = performance.now() - start;
    expect(elapsed).toBeLessThan(5);
  });
});
