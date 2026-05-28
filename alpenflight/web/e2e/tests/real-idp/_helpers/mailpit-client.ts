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
