/**
 * Mailpit REST client for the real-idp suite.
 *
 * Mailpit's `/api/v1/search` accepts a `query` parameter using its own
 * search-grammar (`to:foo@example.com`, `subject:Verify`, ...). The
 * `messages` field contains a paged result with message metadata; full
 * body comes from `/api/v1/message/{id}`.
 *
 * Poll cadence per refinement: 500ms interval, 15s cap. If >1 message
 * matches a per-test unique address, fail loud — that's a test bug, not
 * something to paper over.
 */

const MAILPIT_BASE = process.env['E2E_MAILPIT_BASE'] ?? 'http://localhost:8025';
const MAILPIT_SEARCH = `${MAILPIT_BASE}/api/v1/search`;
const MAILPIT_MESSAGE = `${MAILPIT_BASE}/api/v1/message`;

interface MailpitMessageSummary {
  ID: string;
  Subject: string;
  To: Array<{ Address: string }>;
}

interface MailpitSearchResponse {
  messages: MailpitMessageSummary[];
  total: number;
}

interface MailpitMessageDetail {
  ID: string;
  Subject: string;
  Text: string;
  HTML?: string;
}

async function searchByTo(toAddress: string): Promise<MailpitMessageSummary[]> {
  const url = `${MAILPIT_SEARCH}?query=${encodeURIComponent(`to:${toAddress}`)}&limit=10`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`mailpit search failed (${res.status}): ${await res.text()}`);
  }
  return ((await res.json()) as MailpitSearchResponse).messages ?? [];
}

async function fetchMessage(id: string): Promise<MailpitMessageDetail> {
  const res = await fetch(`${MAILPIT_MESSAGE}/${id}`);
  if (!res.ok) {
    throw new Error(`mailpit fetch ${id} failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as MailpitMessageDetail;
}

export interface WaitForMessageOptions {
  timeoutMs?: number;
  intervalMs?: number;
}

/**
 * Poll Mailpit until a message addressed to `toAddress` arrives. Throws
 * if >1 message matches (test bug) or no message within timeout.
 *
 * Use this for a UNIQUE-per-run recipient (a fresh registration email, a
 * run-tagged crew address): exactly one mail must land, and a second is a
 * real duplicate-bug to surface. For a SHARED address that can legitimately
 * receive more than one mail in a run (e.g. a club notification address the
 * imminent pass mails once per day+1 planning day on a never-truncated
 * tenant), use {@link waitForMessageWithSubject}, which keys on the
 * expected subject instead of demanding a singleton inbox.
 */
export async function waitForMessage(
  toAddress: string,
  options: WaitForMessageOptions = {},
): Promise<MailpitMessageDetail> {
  const timeoutMs = options.timeoutMs ?? 15_000;
  const intervalMs = options.intervalMs ?? 500;
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const matches = await searchByTo(toAddress);
    if (matches.length > 1) {
      throw new Error(
        `mailpit: ${matches.length} messages match to:${toAddress} — expected exactly 1. ` +
          `Test wrote two emails to the same address; do not paper over.`,
      );
    }
    if (matches.length === 1) {
      return fetchMessage(matches[0]!.ID);
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`mailpit: no message to:${toAddress} within ${timeoutMs}ms`);
}

/**
 * Poll Mailpit until a message to `toAddress` WITH the exact `subject`
 * arrives, and return it. For a SHARED recipient address that can hold more
 * than one legitimate mail in a run (the club notification address: the
 * imminent pass mails it once per day+1 planning day, and a never-truncated
 * tenant may carry a second day+1 day — `PlanningDayNotificationJobIT`
 * proves "two day+1 days → two club mails" is the designed behavior). The
 * singleton {@link waitForMessage} is wrong here — it false-fails on a
 * co-located day+1's mail to the same address.
 *
 * Still NOT papering over: a real job duplicate (the SAME mail sent twice)
 * would yield two messages with the SAME subject. We therefore assert that
 * EVERY message to this address carries the expected subject — so an
 * unexpected/extra template (e.g. a stray cancel, or a second template the
 * job should not have sent) surfaces loud — and that the expected one is
 * present. Keying on subject (the template identity) is the honest
 * shared-address contract; an exact inbox-count assertion is not, because
 * the count legitimately tracks the club's day+1 planning-day population.
 */
export async function waitForMessageWithSubject(
  toAddress: string,
  subject: string,
  options: WaitForMessageOptions = {},
): Promise<MailpitMessageDetail> {
  const timeoutMs = options.timeoutMs ?? 15_000;
  const intervalMs = options.intervalMs ?? 500;
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const matches = await searchByTo(toAddress);
    const wrongSubject = matches.find((m) => m.Subject !== subject);
    if (wrongSubject) {
      throw new Error(
        `mailpit: a message to:${toAddress} has subject "${wrongSubject.Subject}" — ` +
          `expected only "${subject}". An unexpected template landed at the shared ` +
          `address; do not paper over.`,
      );
    }
    const expected = matches.find((m) => m.Subject === subject);
    if (expected) {
      return fetchMessage(expected.ID);
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(
    `mailpit: no message to:${toAddress} with subject "${subject}" within ${timeoutMs}ms`,
  );
}

/**
 * Extract the Keycloak verify-email action-token URL from a message body.
 * The href format is the contract — Keycloak's verify-email template
 * always renders `${ISSUER}/login-actions/action-token?key=…` regardless
 * of locale. Subject + surrounding copy are brittle (i18n-prone); the
 * href is not. Parse via regex; click via `page.goto(href)`.
 */
export function extractVerifyLink(message: MailpitMessageDetail): string {
  // Greedy across the full body to dodge an HTML attribute that may
  // wrap the URL across line breaks. The `key=` token is single-use +
  // signed; never extract / log it — the surrounding URL is what we click.
  const body = message.HTML ?? message.Text ?? '';
  const match = body.match(
    /https?:\/\/[^\s"'<>]*\/realms\/alpenflight\/login-actions\/action-token\?key=[^\s"'<>&]+(?:&[^\s"'<>]*)?/,
  );
  if (!match) {
    throw new Error(
      `mailpit: no verify-link in message ${message.ID} (subject='${message.Subject}')`,
    );
  }
  return match[0];
}

/**
 * Best-effort inbox purge. Called by afterEach as a courtesy (not a
 * correctness gate — Mailpit is a sink, growing inboxes never break the
 * suite, but a clean inbox makes manual debugging easier).
 */
export async function purgeMailpit(): Promise<void> {
  const res = await fetch(`${MAILPIT_BASE}/api/v1/messages`, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) {
    // eslint-disable-next-line no-console
    console.warn(`mailpit purge failed (${res.status}): ${await res.text()}`);
  }
}

export async function mailpitInfo(): Promise<unknown> {
  const res = await fetch(`${MAILPIT_BASE}/api/v1/info`);
  if (!res.ok) {
    throw new Error(`mailpit /api/v1/info ${res.status}`);
  }
  return res.json();
}
