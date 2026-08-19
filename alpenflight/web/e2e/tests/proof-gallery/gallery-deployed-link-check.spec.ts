import { type APIRequestContext } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

const CDN_PROPAGATION_BUDGET_MS = 180_000;
const WALL_CEILING_WHILE_GITHUB_STILL_BUILDS_THE_PAGE_MS = 240_000;
const DEPLOYED_TEST_TIMEOUT_MS = WALL_CEILING_WHILE_GITHUB_STILL_BUILDS_THE_PAGE_MS + 30_000;
const RETRY_INTERVAL_MS = 5_000;

const PAGES_API_REPOSITORY = process.env['GITHUB_REPOSITORY'];
const PAGES_API_TOKEN = process.env['GITHUB_TOKEN'];

interface PagesBuild {
  status: string;
  detail: string;
}

const PAGES_BUILD_STATUS_UNREADABLE = 'unreadable';

const EXPECT_GENERATED_AT = (() => {
  const raw = process.env['GALLERY_EXPECT_GENERATED_AT']?.trim();
  return raw && Number.isFinite(Date.parse(raw)) ? raw : undefined;
})();

function pageUrl(deployed: string): string {
  if (/\.html$/.test(deployed)) return deployed;
  return new URL('index.html', deployed.endsWith('/') ? deployed : `${deployed}/`).href;
}

async function getFresh(request: APIRequestContext, url: string) {
  const u = new URL(url);
  u.searchParams.set('_nocache', `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
  return request.get(u.href, {
    headers: { 'cache-control': 'no-cache', pragma: 'no-cache' },
  });
}

interface PagesBuildFromApi {
  status?: string;
  commit?: string;
  created_at?: string;
  error?: { message?: string | null };
}

function buildsStartedAfterThisRunGeneratedThePage(
  builds: PagesBuildFromApi[],
): PagesBuildFromApi[] {
  if (!EXPECT_GENERATED_AT) return builds;
  const publishedAtMs = Date.parse(EXPECT_GENERATED_AT);
  const ours = builds.filter((b) => Date.parse(b.created_at ?? '') >= publishedAtMs);
  return ours.length > 0 ? ours : builds.slice(0, 1);
}

async function readPagesBuildThisRunTriggered(request: APIRequestContext): Promise<PagesBuild> {
  if (!PAGES_API_REPOSITORY || !PAGES_API_TOKEN) {
    return {
      status: PAGES_BUILD_STATUS_UNREADABLE,
      detail:
        'the GitHub Pages build status was NOT read (set GITHUB_REPOSITORY + GITHUB_TOKEN with `pages: read` to tell a failed build apart from CDN lag)',
    };
  }
  const res = await request.get(
    `https://api.github.com/repos/${PAGES_API_REPOSITORY}/pages/builds?per_page=20`,
    {
      headers: {
        accept: 'application/vnd.github+json',
        authorization: `Bearer ${PAGES_API_TOKEN}`,
        'x-github-api-version': '2022-11-28',
      },
      failOnStatusCode: false,
    },
  );
  if (!res.ok()) {
    return {
      status: PAGES_BUILD_STATUS_UNREADABLE,
      detail: `the GitHub Pages build status was NOT read (builds API answered ${res.status()})`,
    };
  }
  const candidates = buildsStartedAfterThisRunGeneratedThePage(
    (await res.json()) as PagesBuildFromApi[],
  );
  const errored = candidates.find((b) => b.status === 'errored');
  if (errored) {
    return {
      status: 'errored',
      detail:
        `GitHub Pages FAILED TO BUILD the site pushed as commit ${errored.commit ?? 'unknown'} ` +
        `at ${errored.created_at ?? 'unknown time'}. The builds API reports: ` +
        `"${errored.error?.message ?? '(no message)'}". The git push to gh-pages succeeded, so ` +
        'the CDN keeps serving the PREVIOUS copy — this is NOT CDN lag, and polling will never ' +
        'clear it. The usual cause is the published site crossing the 1 GB GitHub Pages cap; run ' +
        'the gh-pages-retention workflow, then re-publish.',
    };
  }
  const newest = candidates[0];
  if (!newest) {
    return {
      status: PAGES_BUILD_STATUS_UNREADABLE,
      detail: 'the GitHub Pages builds API listed no build at all',
    };
  }
  return {
    status: newest.status ?? PAGES_BUILD_STATUS_UNREADABLE,
    detail:
      `the GitHub Pages build for commit ${newest.commit ?? 'unknown'} reports status ` +
      `"${newest.status ?? PAGES_BUILD_STATUS_UNREADABLE}", so the publish itself is not the fault`,
  };
}

function stalenessReason(html: string): string {
  if (!EXPECT_GENERATED_AT) return '';
  const served = /<meta name="proof-generated-at" content="([^"]+)">/i.exec(html)?.[1];
  if (!served) {
    return `served page carries no proof-generated-at stamp — a pre-stamp copy, not the page this run published (${EXPECT_GENERATED_AT})`;
  }
  if (Date.parse(served) < Date.parse(EXPECT_GENERATED_AT)) {
    return `stale CDN copy: served page was generated ${served}, this run published ${EXPECT_GENERATED_AT}`;
  }
  return '';
}

const FRAGMENT_OR_EXTERNAL_REF = /^(?:#|[a-z]+:|\/\/)/i;
const EXTERNAL_REF = /^(?:[a-z]+:|\/\/)/i;

function extractHrefs(html: string): string[] {
  return [...new Set([...html.matchAll(/<a\b[^>]*\bhref="([^"]+)"/gi)].map((m) => m[1]!))];
}

function extractAssetSrcs(html: string): string[] {
  return [
    ...new Set(
      [...html.matchAll(/<(?:img|video|source)\b[^>]*\bsrc="([^"]+)"/gi)].map((m) => m[1]!),
    ),
  ];
}

async function checkOnce(request: APIRequestContext, url: string): Promise<string[]> {
  const broken: string[] = [];
  const res = await getFresh(request, url);
  if (!res.ok()) return [`${url} → ${res.status()}`];
  const html = await res.text();
  const stale = stalenessReason(html);
  if (stale) return [`${url} → ${stale}`];

  for (const ref of [...extractAssetSrcs(html), ...extractHrefs(html)]) {
    if (FRAGMENT_OR_EXTERNAL_REF.test(ref)) continue;
    const abs = new URL(ref, url).href;
    const r = await getFresh(request, abs);
    if (!r.ok()) broken.push(`${url} → ${abs} (${r.status()})`);
  }
  return broken;
}

async function pollUntilPublishedOrProvenBroken(
  request: APIRequestContext,
  attempt: () => Promise<string[]>,
): Promise<{ remaining: string[]; build: PagesBuild }> {
  const startedAtMs = Date.now();
  const wallCeilingMs = startedAtMs + WALL_CEILING_WHILE_GITHUB_STILL_BUILDS_THE_PAGE_MS;
  let deadlineMs = startedAtMs + CDN_PROPAGATION_BUDGET_MS;
  let build: PagesBuild = { status: PAGES_BUILD_STATUS_UNREADABLE, detail: 'not read yet' };
  for (;;) {
    const remaining = await attempt();
    if (remaining.length === 0) return { remaining, build };
    build = await readPagesBuildThisRunTriggered(request);
    if (build.status === 'errored') return { remaining, build };
    if (build.status === 'building' || build.status === 'queued') {
      deadlineMs = Math.min(wallCeilingMs, Date.now() + CDN_PROPAGATION_BUDGET_MS);
    }
    if (Date.now() >= deadlineMs) return { remaining, build };
    await new Promise((r) => setTimeout(r, RETRY_INTERVAL_MS));
  }
}

async function checkWithRetry(request: APIRequestContext, url: string): Promise<void> {
  const { remaining, build } = await pollUntilPublishedOrProvenBroken(request, () =>
    checkOnce(request, url),
  );
  if (build.status === 'errored') {
    expect(build.status, build.detail).not.toBe('errored');
  }
  expect(
    remaining,
    `live dead links (after retry) — ${build.detail}:\n  - ${remaining.join('\n  - ')}`,
  ).toEqual([]);
}

const DEPLOYED_GALLERY_URL = process.env['GALLERY_DEPLOYED_URL'];
const DEPLOYED_GALLERY_JOURNEY = process.env['GALLERY_DEPLOYED_JOURNEY'];

test.describe('proof-gallery deployed bookmark', () => {
  test('[deployed] the single bookmark page + every declared asset resolve 200', async ({
    request,
  }) => {
    test.skip(
      !DEPLOYED_GALLERY_URL,
      'set GALLERY_DEPLOYED_URL to check the live gh-pages bookmark',
    );
    test.setTimeout(DEPLOYED_TEST_TIMEOUT_MS);
    await checkWithRetry(request, pageUrl(DEPLOYED_GALLERY_URL!));
  });

  test('[deployed-journey] the bookmark is the in-flight journey + not a thin page', async ({
    request,
  }) => {
    test.skip(
      !DEPLOYED_GALLERY_URL || !DEPLOYED_GALLERY_JOURNEY,
      'set GALLERY_DEPLOYED_URL + GALLERY_DEPLOYED_JOURNEY to assert the in-flight page',
    );
    test.setTimeout(DEPLOYED_TEST_TIMEOUT_MS);
    const url = pageUrl(DEPLOYED_GALLERY_URL!);

    const { remaining, build } = await pollUntilPublishedOrProvenBroken(request, async () => {
      const reason = await tryAssertJourneyPage(request, url, DEPLOYED_GALLERY_JOURNEY!);
      return reason === '' ? [] : [reason];
    });
    if (build.status === 'errored') {
      expect(build.status, build.detail).not.toBe('errored');
    }
    expect(
      remaining.join('\n  '),
      `deployed in-flight page check (after retry) — ${build.detail}:\n  ${remaining.join('\n  ')}`,
    ).toBe('');
  });
});

async function tryAssertJourneyPage(
  request: APIRequestContext,
  url: string,
  journey: string,
): Promise<string> {
  const res = await getFresh(request, url);
  if (!res.ok()) return `bookmark page ${url} → ${res.status()}`;
  const html = await res.text();

  const stale = stalenessReason(html);
  if (stale) return `bookmark page ${url}: ${stale}`;

  if (!html.includes(`>${journey} — proof</h1>`)) {
    return `bookmark page ${url} is not journey ${journey}'s page (no "<h1>${journey} — proof</h1>")`;
  }
  const assets = extractAssetSrcs(html).filter((s) => !/^#/.test(s));
  if (assets.length === 0) {
    return `bookmark page ${url} declares NO video/screenshot assets (thin/pending page)`;
  }
  const broken: string[] = [];
  for (const src of assets) {
    if (EXTERNAL_REF.test(src)) continue;
    const abs = new URL(src, url).href;
    const r = await getFresh(request, abs);
    if (!r.ok()) broken.push(`${abs} (${r.status()})`);
  }
  if (broken.length) {
    return `bookmark page ${url} has ${broken.length} asset(s) not resolving 200:\n    - ${broken.join('\n    - ')}`;
  }
  console.log(
    `[deployed-journey] ${journey}: ${assets.length} live asset(s) on ${url} (generated-at ${
      /<meta name="proof-generated-at" content="([^"]+)">/i.exec(html)?.[1] ?? 'unstamped'
    })`,
  );
  return '';
}
