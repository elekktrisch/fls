const MAILPIT_BASE = process.env.MAILPIT_BASE ?? 'http://localhost:8025';

export type MailpitAddress = { Address: string; Name?: string };

export type MailpitMessage = {
  ID: string;
  MessageID?: string;
  Subject: string;
  To: MailpitAddress[];
  From: MailpitAddress;
  Created: string;
  Snippet?: string;
};

type MailpitListResponse = {
  total: number;
  unread: number;
  count: number;
  messages: MailpitMessage[];
};

type MailpitMessageDetail = {
  ID: string;
  Subject: string;
  Text: string;
  HTML: string;
  To: MailpitAddress[];
  From: MailpitAddress;
};

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`Mailpit request failed: ${res.status} ${res.statusText} ${url} -- ${body}`);
  }
  return (await res.json()) as T;
}

export async function clearInbox(): Promise<void> {
  const res = await fetch(`${MAILPIT_BASE}/api/v1/messages`, { method: 'DELETE' });
  if (!res.ok) {
    throw new Error(`clearInbox failed: ${res.status} ${res.statusText}`);
  }
}

export async function clearInboxForRecipient(to: string): Promise<void> {
  const list = await listInboxForRecipient(to);
  if (list.length === 0) return;
  const ids = list.map(m => m.ID);
  const res = await fetch(`${MAILPIT_BASE}/api/v1/messages`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ IDs: ids }),
  });
  if (!res.ok) {
    throw new Error(`clearInboxForRecipient failed: ${res.status} ${res.statusText}`);
  }
}

export async function listInbox(): Promise<MailpitMessage[]> {
  const data = await fetchJson<MailpitListResponse>(`${MAILPIT_BASE}/api/v1/messages?limit=200`);
  return data.messages;
}

export async function listInboxForRecipient(to: string): Promise<MailpitMessage[]> {
  const query = encodeURIComponent(`to:${to}`);
  const data = await fetchJson<MailpitListResponse>(
    `${MAILPIT_BASE}/api/v1/search?query=${query}&limit=200`,
  );
  return data.messages;
}

export async function getBody(messageId: string): Promise<{ text: string; html: string }> {
  const data = await fetchJson<MailpitMessageDetail>(
    `${MAILPIT_BASE}/api/v1/message/${messageId}`,
  );
  return { text: data.Text ?? '', html: data.HTML ?? '' };
}

export type EmailFilter = {
  to?: string;
  subjectMatches?: RegExp;
  bodyMatches?: RegExp;
  from?: string;
  createdAfter?: Date;
};

export type ExpectOptions = {
  timeout?: number;
  pollIntervalMs?: number;
};

function matchesAddress(addrs: MailpitAddress[], wanted: string): boolean {
  const w = wanted.toLowerCase();
  return addrs.some(a => a.Address.toLowerCase() === w);
}

async function evaluateFilter(
  messages: MailpitMessage[],
  filter: EmailFilter,
): Promise<MailpitMessage | null> {
  for (const msg of messages) {
    if (filter.to && !matchesAddress(msg.To, filter.to)) continue;
    if (filter.from && !matchesAddress([msg.From], filter.from)) continue;
    if (filter.subjectMatches && !filter.subjectMatches.test(msg.Subject ?? '')) continue;
    if (filter.createdAfter) {
      const t = Date.parse(msg.Created);
      if (!Number.isFinite(t) || t < filter.createdAfter.getTime()) continue;
    }
    if (filter.bodyMatches) {
      const body = await getBody(msg.ID);
      const haystack = (body.text || '') + '\n' + (body.html || '');
      if (!filter.bodyMatches.test(haystack)) continue;
    }
    return msg;
  }
  return null;
}

export async function expectEmail(
  filter: EmailFilter,
  opts: ExpectOptions = {},
): Promise<MailpitMessage> {
  const timeout = opts.timeout ?? 10_000;
  const interval = opts.pollIntervalMs ?? 250;
  const deadline = Date.now() + timeout;

  let lastSeenCount = -1;
  while (true) {
    const messages = filter.to
      ? await listInboxForRecipient(filter.to)
      : await listInbox();
    lastSeenCount = messages.length;
    const hit = await evaluateFilter(messages, filter);
    if (hit) return hit;

    if (Date.now() >= deadline) {
      const seen = messages.slice(0, 10).map(m =>
        `  - to=${m.To.map(t => t.Address).join(',')} subj=${JSON.stringify(m.Subject)}`,
      ).join('\n');
      throw new Error(
        `expectEmail timed out after ${timeout}ms.\n` +
          `Filter: ${JSON.stringify({
            to: filter.to,
            subject: filter.subjectMatches?.source,
            body: filter.bodyMatches?.source,
            from: filter.from,
          })}\n` +
          `Messages seen (${lastSeenCount}):\n${seen || '  (none)'}`,
      );
    }
    await new Promise(r => setTimeout(r, interval));
  }
}

export async function expectNoEmail(
  filter: { to: string },
  opts: { window: number },
): Promise<void> {
  const deadline = Date.now() + opts.window;
  while (Date.now() < deadline) {
    const messages = await listInboxForRecipient(filter.to);
    if (messages.length > 0) {
      throw new Error(
        `expectNoEmail: found ${messages.length} message(s) addressed to ${filter.to}: ` +
          messages.map(m => JSON.stringify(m.Subject)).join(', '),
      );
    }
    await new Promise(r => setTimeout(r, 200));
  }
}

export function uniqueRecipient(prefix = 'e2e-email'): string {
  const id = Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
  return `${prefix}-${id}@e2e.fls.local`;
}

export async function findMessage(
  filter: EmailFilter,
  opts: ExpectOptions = {},
): Promise<MailpitMessage & { Text: string; HTML: string }> {
  const msg = await expectEmail(filter, opts);
  const body = await getBody(msg.ID);
  return { ...msg, Text: body.text, HTML: body.html };
}
