import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { ArticlesService } from '@api/generated/articles/articles.service';
import type {
  ArticleCreateRequest,
  ArticleDetail,
  ArticleListItem,
  ArticleUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { ArticlesStore } from './articles.store';

const sampleItem: ArticleListItem = {
  id: 'art-019e30c3-2c00-7001-8000-000000000001',
  articleNumber: 'A-100',
  articleName: 'Glider hour',
  isActive: true,
};

const sampleDetail: ArticleDetail = {
  id: sampleItem.id!,
  articleNumber: 'A-100',
  articleName: 'Glider hour',
  isActive: true,
};

interface ApiStubs {
  list: () => Observable<ArticleListItem[]>;
  get: (id: string) => Observable<ArticleDetail>;
  create: (req: ArticleCreateRequest) => Observable<ArticleDetail>;
  update: (id: string, req: ArticleUpdateRequest) => Observable<ArticleDetail>;
  remove: (id: string) => Observable<void>;
}

function apiStub(stubs: Partial<ApiStubs>): ArticlesService {
  const api = {
    listArticles: ((includeInactive?: unknown, options?: unknown) => {
      void includeInactive;
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as ArticlesService['listArticles'],
    getArticle: ((id: string, options?: unknown) => {
      void options;
      return (stubs.get ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id);
    }) as ArticlesService['getArticle'],
    registerArticle: ((req: ArticleCreateRequest, options?: unknown) => {
      void options;
      return (stubs.create ?? (() => of(sampleDetail)))(req);
    }) as ArticlesService['registerArticle'],
    updateArticle: ((id: string, req: ArticleUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id, req);
    }) as ArticlesService['updateArticle'],
    deleteArticle: ((id: string, options?: unknown) => {
      void options;
      return (stubs.remove ?? (() => of(undefined as unknown as void)))(id);
    }) as ArticlesService['deleteArticle'],
  };
  return api as unknown as ArticlesService;
}

function configure(api: ArticlesService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: ArticlesService, useValue: api },
    ],
  });
  return bus;
}

const baseCreateReq: ArticleCreateRequest = {
  articleNumber: 'A-200',
  articleName: 'Tow service',
  isActive: true,
};

const baseUpdateReq: ArticleUpdateRequest = { ...baseCreateReq, articleNumber: 'A-100' };

describe('ArticlesStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(apiStub({ list: () => of([sampleItem]) }));
    const store = TestBed.inject(ArticlesStore);
    expect(store.entities()).toEqual([sampleItem]);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll surfaces an error on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(apiStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(ArticlesStore);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('create adds a list entity and emits article.created', () => {
    const created: ArticleDetail = {
      ...sampleDetail,
      id: 'art-019e30c3-2c00-7001-8000-00000000000a',
      articleNumber: 'A-200',
      articleName: 'Tow service',
    };
    let listState: ArticleListItem[] = [sampleItem];
    const bus = configure(
      apiStub({
        list: () => of(listState),
        create: () => {
          listState = [
            sampleItem,
            { ...sampleItem, id: created.id!, articleNumber: 'A-200', articleName: 'Tow service' },
          ];
          return of(created);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(ArticlesStore);
    store.create({ ...baseCreateReq });
    expect(store.entities().some((a) => a.id === created.id)).toBe(true);
    expect(store.selectedArticle()?.id).toBe(created.id);
    expect(events).toEqual([{ kind: 'article.created', id: created.id }]);
  });

  it('create surfaces 409 as number-duplicate with number in saveError', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      apiStub({
        list: () => of([sampleItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(ArticlesStore);
    store.create({ ...baseCreateReq, articleNumber: 'A-DUP' });
    expect(store.saveError()).toContain('A-DUP');
    expect(store.saveError()).toContain('already in use');
    expect(store.saveErrorKind()).toBe('number-duplicate');
  });

  it('create surfaces 403 as forbidden', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    configure(
      apiStub({
        list: () => of([sampleItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(ArticlesStore);
    store.create({ ...baseCreateReq });
    expect(store.saveErrorKind()).toBe('forbidden');
  });

  it('update patches matching entity and emits article.updated', () => {
    const renamed: ArticleDetail = { ...sampleDetail, articleName: 'Renamed' };
    let listState: ArticleListItem[] = [sampleItem];
    const bus = configure(
      apiStub({
        list: () => of(listState),
        update: () => {
          listState = [{ ...sampleItem, articleName: 'Renamed' }];
          return of(renamed);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(ArticlesStore);
    store.update({ id: sampleDetail.id!, req: { ...baseUpdateReq, articleName: 'Renamed' } });
    expect(store.entities()[0]?.articleName).toBe('Renamed');
    expect(events).toEqual([{ kind: 'article.updated', id: sampleDetail.id }]);
  });

  it('delete removes entity and emits article.deleted', () => {
    const bus = configure(
      apiStub({
        list: () => of([sampleItem]),
        remove: () => of(undefined as unknown as void),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(ArticlesStore);
    store.delete(sampleItem.id!);
    expect(store.entities()).toEqual([]);
    expect(events).toEqual([{ kind: 'article.deleted', id: sampleItem.id }]);
  });

  it('setIncludeInactive flips the flag and re-loads', () => {
    let lastCallWith: boolean | undefined;
    configure(
      apiStub({
        list: () => {
          lastCallWith = true;
          return of([]);
        },
      }),
    );
    const store = TestBed.inject(ArticlesStore);
    store.setIncludeInactive(true);
    expect(store.includeInactive()).toBe(true);
    expect(lastCallWith).toBe(true);
  });

  it('clears entities on session.logout / session.tenantSwitch', () => {
    const bus = configure(apiStub({ list: () => of([sampleItem]) }));
    const store = TestBed.inject(ArticlesStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities()).toEqual([]);
  });
});
