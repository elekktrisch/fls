import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { EmailTemplatesService } from '@api/generated/email-templates/email-templates.service';
import type { EmailTemplateListItem, EmailTemplateSaveRequest } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { EmailTemplatesStore } from './email-templates.store';


const fileDefault: EmailTemplateListItem = {
  templateKey: 'lostpassword',
  languageLocale: 'de',
  source: 'FILE_DEFAULT',
  body: 'Default body',
};

const clubOverride: EmailTemplateListItem = {
  templateKey: 'lostpassword',
  languageLocale: 'de',
  source: 'CLUB_OVERRIDE',
  subject: 'Reset your password',
  body: 'Override body',
};

const saveRequest: EmailTemplateSaveRequest = {
  subject: 'Reset your password',
  body: 'Override body',
};

interface ApiStubs {
  list: () => Observable<EmailTemplateListItem[]>;
  customize: (
    templateKey: string,
    languageLocale: string,
    req: EmailTemplateSaveRequest,
  ) => Observable<EmailTemplateListItem>;
  reset: (templateKey: string, languageLocale: string) => Observable<void>;
}

function emailTemplatesServiceStub(stubs: Partial<ApiStubs>): EmailTemplatesService {
  const api = {
    listTemplates: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([fileDefault])))();
    }) as EmailTemplatesService['listTemplates'],
    customize: ((
      templateKey: string,
      languageLocale: string,
      req: EmailTemplateSaveRequest,
      options?: unknown,
    ) => {
      void options;
      return (stubs.customize ?? (() => of(clubOverride)))(templateKey, languageLocale, req);
    }) as EmailTemplatesService['customize'],
    reset: ((templateKey: string, languageLocale: string, options?: unknown) => {
      void options;
      return (stubs.reset ?? (() => of(undefined)))(templateKey, languageLocale);
    }) as EmailTemplatesService['reset'],
  };
  return api as unknown as EmailTemplatesService;
}

function configure(api: EmailTemplatesService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: EmailTemplatesService, useValue: api },
    ],
  });
  return bus;
}

describe('EmailTemplatesStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('load populates the union list', () => {
    configure(emailTemplatesServiceStub({ list: () => of([fileDefault, clubOverride]) }));

    const store = TestBed.inject(EmailTemplatesStore);

    expect(store.items()).toEqual([fileDefault, clubOverride]);
    expect(store.loadError()).toBeNull();
    expect(store.isEmpty()).toBe(false);
  });

  it('save PUTs the override then refreshes the list', () => {
    const calls: string[] = [];
    let savePayload: EmailTemplateSaveRequest | null = null;
    let listState: EmailTemplateListItem[] = [fileDefault];
    configure(
      emailTemplatesServiceStub({
        list: () => {
          calls.push('list');
          return of(listState);
        },
        customize: (templateKey, languageLocale, req) => {
          calls.push(`put:${templateKey}/${languageLocale}`);
          savePayload = req;
          listState = [clubOverride];
          return of(clubOverride);
        },
      }),
    );

    const store = TestBed.inject(EmailTemplatesStore);
    store.save({ templateKey: 'lostpassword', languageLocale: 'de', request: saveRequest });

    expect(calls).toEqual(['list', 'put:lostpassword/de', 'list']);
    expect(savePayload).toEqual(saveRequest);
    expect(store.items()).toEqual([clubOverride]);
    expect(store.saveError()).toBeNull();
  });

  it('reset DELETEs the override then refreshes the list', () => {
    const calls: string[] = [];
    let listState: EmailTemplateListItem[] = [clubOverride];
    configure(
      emailTemplatesServiceStub({
        list: () => {
          calls.push('list');
          return of(listState);
        },
        reset: (templateKey, languageLocale) => {
          calls.push(`delete:${templateKey}/${languageLocale}`);
          listState = [fileDefault];
          return of(undefined);
        },
      }),
    );

    const store = TestBed.inject(EmailTemplatesStore);
    store.reset({ templateKey: 'lostpassword', languageLocale: 'de' });

    expect(calls).toEqual(['list', 'delete:lostpassword/de', 'list']);
    expect(store.items()).toEqual([fileDefault]);
    expect(store.saveError()).toBeNull();
  });

  it('surfaces a write error in saveError and does NOT refresh', () => {
    const calls: string[] = [];
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    configure(
      emailTemplatesServiceStub({
        list: () => {
          calls.push('list');
          return of([fileDefault]);
        },
        customize: () => throwError(() => err),
      }),
    );

    const store = TestBed.inject(EmailTemplatesStore);
    store.save({ templateKey: 'lostpassword', languageLocale: 'de', request: saveRequest });

    expect(calls).toEqual(['list']);
    expect(store.saveError()).not.toBeNull();
    expect(store.saveErrorKind()).toBe('forbidden');
    expect(store.isSaving()).toBe(false);
  });
});
