
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

export async function waitForMessageWithBody(
  toAddress: string,
  subject: string,
  needle: string,
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
    for (const summary of matches) {
      const message = await fetchMessage(summary.ID);
      if ((message.HTML ?? message.Text ?? '').includes(needle)) {
        return message;
      }
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(
    `mailpit: no message to:${toAddress} with subject "${subject}" mentioning "${needle}" ` +
      `within ${timeoutMs}ms`,
  );
}

export function extractVerifyLink(message: MailpitMessageDetail): string {
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
