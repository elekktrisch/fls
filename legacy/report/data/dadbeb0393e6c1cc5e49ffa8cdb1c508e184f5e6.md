# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: public/registration-flows.spec.ts >> public:passengerflight registration creates Person row (email delivery annotated, not asserted)
- Location: tests/public/registration-flows.spec.ts:101:5

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator:  locator('[data-testid="success-message"]')
Expected: visible
Received: hidden
Timeout:  10000ms

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for locator('[data-testid="success-message"]')
    23 × locator resolved to <div ng-show="ctrl.success" data-testid="success-message" class="col-xs-12 col-sm-12 col-md-6 col-lg-6 ng-scope ng-hide">…</div>
       - unexpected value "hidden"

```

```yaml
- heading [level=1]
- paragraph
- form:
  - text: ":"
  - textbox ":": E2EUser
  - text: ":"
  - textbox ":": Pax040538
  - text: ":"
  - textbox ":": Teststrasse 2
  - text: ":"
  - textbox ":": "5678"
  - text: ":"
  - textbox ":": Teststadt
  - text: ":"
  - textbox ":"
  - text: ":"
  - textbox ":"
  - text: ":"
  - textbox ":"
  - text: ":"
  - textbox ":": passenger-1793442040538-242708@e2e.fls.local
  - text: ":"
  - textbox ":"
  - text: ":"
  - checkbox [checked]
  - button
- paragraph: Der Passagierflug kostet CHF 200.- und wird vom Sekretariat nach der Registrierung in Rechnung gestellt.
- paragraph: Passagierflüge werden hauptsächlich an Samstagen oder Sonntagen durchgeführt. Für einen Termin wenden Sie sich bitte an die entsprechende Kontaktperson, welche auf dem Gutschein aufgeführt ist.
```

# Test source

```ts
  20  | 
  21  | async function withPool<T>(fn: (pool: sql.ConnectionPool) => Promise<T>): Promise<T> {
  22  |   const pool = await new sql.ConnectionPool(MSSQL_CONFIG).connect();
  23  |   try {
  24  |     return await fn(pool);
  25  |   } finally {
  26  |     await pool.close();
  27  |   }
  28  | }
  29  | 
  30  | function uniqueEmail(kind: 'trial' | 'passenger'): string {
  31  |   const nonce = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  32  |   return `${kind}-${nonce}@e2e.fls.local`;
  33  | }
  34  | 
  35  | async function injectSelectedDayBecauseAvailableDatesIsHardcodedToAnotherClub(
  36  |   page: Page,
  37  | ): Promise<void> {
  38  |   await page.evaluate(() => {
  39  |     const w = window as any;
  40  |     const elem = w.angular.element(document.querySelector('[data-testid="trial-flight-form"]'));
  41  |     const ctrl = elem.scope().ctrl;
  42  |     ctrl.selectedDay = { date: '2099-07-01T00:00:00' };
  43  |     elem.scope().$apply();
  44  |   });
  45  | }
  46  | 
  47  | test('public:trialflight registration creates Person row (email delivery annotated, not asserted)', async ({ page }) => {
  48  |   const submittedAt = new Date();
  49  |   const email = uniqueEmail('trial');
  50  |   const firstName = `Trial${Date.now().toString().slice(-6)}`;
  51  |   const lastName = 'E2EUser';
  52  | 
  53  |   await page.goto(`${WEB_BASE}/#/trialflight?club=${TEST_CLUB_KEY}`);
  54  |   const form = page.locator('[data-testid="trial-flight-form"]');
  55  |   await form.waitFor({ state: 'visible', timeout: 10_000 });
  56  | 
  57  |   await form.locator('input#name').fill(lastName);
  58  |   await form.locator('input#firstname').fill(firstName);
  59  |   await form.locator('input#AddressLine1').fill('Teststrasse 1');
  60  |   await form.locator('input#zip').fill('1234');
  61  |   await form.locator('input#city').fill('Teststadt');
  62  |   await form.locator('input#PrivateEmail').fill(email);
  63  | 
  64  |   await injectSelectedDayBecauseAvailableDatesIsHardcodedToAnotherClub(page);
  65  | 
  66  |   await form.locator('[data-testid="submit"]').click();
  67  | 
  68  |   await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });
  69  | 
  70  |   const row = await withPool(async pool => {
  71  |     const r = await pool.request()
  72  |       .input('email', sql.NVarChar, email)
  73  |       .query(`SELECT TOP 1 Firstname, Lastname, EmailPrivate
  74  |                 FROM Persons WHERE EmailPrivate = @email
  75  |                 ORDER BY CreatedOn DESC`);
  76  |     return r.recordset[0];
  77  |   });
  78  |   expect(row, `expected Persons row for ${email}`).toBeDefined();
  79  |   expect(row.Firstname).toBe(firstName);
  80  |   expect(row.Lastname).toBe(lastName);
  81  |   expect(row.EmailPrivate).toBe(email);
  82  | 
  83  |   try {
  84  |     const mail = await findMessage(
  85  |       { to: email, createdAfter: submittedAt, subjectMatches: /Schnupperflug/i },
  86  |       { timeout: 8_000 },
  87  |     );
  88  |     test.info().annotations.push({
  89  |       type: 'mailpit',
  90  |       description: `Trial-flight email delivered: subject="${mail.Subject}"`,
  91  |     });
  92  |   } catch (err) {
  93  |     test.info().annotations.push({
  94  |       type: 'mailpit:missing',
  95  |       description: `No trial-flight email found for ${email}: ${(err as Error).message}`,
  96  |     });
  97  |   }
  98  |   await screenshot(page, 'registration-flows-01');
  99  | });
  100 | 
  101 | test('public:passengerflight registration creates Person row (email delivery annotated, not asserted)', async ({ page }) => {
  102 |   const submittedAt = new Date();
  103 |   const email = uniqueEmail('passenger');
  104 |   const firstName = `Pax${Date.now().toString().slice(-6)}`;
  105 |   const lastName = 'E2EUser';
  106 | 
  107 |   await page.goto(`${WEB_BASE}/#/passengerflight?club=${TEST_CLUB_KEY}`);
  108 |   const form = page.locator('[data-testid="passenger-flight-form"]');
  109 |   await form.waitFor({ state: 'visible', timeout: 10_000 });
  110 | 
  111 |   await form.locator('input#name').fill(lastName);
  112 |   await form.locator('input#firstname').fill(firstName);
  113 |   await form.locator('input#AddressLine1').fill('Teststrasse 2');
  114 |   await form.locator('input#zip').fill('5678');
  115 |   await form.locator('input#city').fill('Teststadt');
  116 |   await form.locator('input#PrivateEmail').fill(email);
  117 | 
  118 |   await form.locator('[data-testid="submit"]').click();
  119 | 
> 120 |   await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });
      |                                                                 ^ Error: expect(locator).toBeVisible() failed
  121 | 
  122 |   const row = await withPool(async pool => {
  123 |     const r = await pool.request()
  124 |       .input('email', sql.NVarChar, email)
  125 |       .query(`SELECT TOP 1 Firstname, Lastname, EmailPrivate
  126 |                 FROM Persons WHERE EmailPrivate = @email
  127 |                 ORDER BY CreatedOn DESC`);
  128 |     return r.recordset[0];
  129 |   });
  130 |   expect(row, `expected Persons row for ${email}`).toBeDefined();
  131 |   expect(row.Firstname).toBe(firstName);
  132 |   expect(row.Lastname).toBe(lastName);
  133 |   expect(row.EmailPrivate).toBe(email);
  134 | 
  135 |   try {
  136 |     const mail = await findMessage(
  137 |       { to: email, createdAfter: submittedAt, subjectMatches: /Passagier/i },
  138 |       { timeout: 8_000 },
  139 |     );
  140 |     test.info().annotations.push({
  141 |       type: 'mailpit',
  142 |       description: `Passenger-flight email delivered: subject="${mail.Subject}"`,
  143 |     });
  144 |   } catch (err) {
  145 |     test.info().annotations.push({
  146 |       type: 'mailpit:missing',
  147 |       description: `No passenger-flight email found for ${email}: ${(err as Error).message}`,
  148 |     });
  149 |   }
  150 |   await screenshot(page, 'registration-flows-02');
  151 | });
  152 | 
  153 | test('public:lostpassword sends reset email to seeded user', async ({ page }) => {
  154 |   const submittedAt = new Date();
  155 | 
  156 |   await page.goto(`${WEB_BASE}/#/lostpassword`);
  157 |   const form = page.locator('[data-testid="lostpassword-form"]');
  158 |   await form.waitFor({ state: 'visible', timeout: 10_000 });
  159 | 
  160 |   await form.locator('input[type="text"]').first().fill('testclubadmin');
  161 |   await form.locator('[data-testid="submit"]').click();
  162 | 
  163 |   await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });
  164 | 
  165 |   const mail = await findMessage(
  166 |     {
  167 |       to: TESTCLUBADMIN_EMAIL,
  168 |       subjectMatches: /Passwort-?Reset/i,
  169 |       createdAfter: submittedAt,
  170 |     },
  171 |     { timeout: 10_000 },
  172 |   );
  173 |   expect(mail.To.some(r => r.Address.toLowerCase() === TESTCLUBADMIN_EMAIL)).toBeTruthy();
  174 |   expect(mail.Text + mail.HTML).toMatch(/userid=[a-f0-9-]+&code=/i);
  175 |   await screenshot(page, 'registration-flows-03');
  176 | });
  177 | 
  178 | test('public:confirm renders the password-set form when given a real reset token', async ({ page }) => {
  179 |   const submittedAt = new Date();
  180 | 
  181 |   await page.goto(`${WEB_BASE}/#/lostpassword`);
  182 |   const form = page.locator('[data-testid="lostpassword-form"]');
  183 |   await form.waitFor({ state: 'visible', timeout: 10_000 });
  184 |   await form.locator('input[type="text"]').first().fill('testclubadmin');
  185 |   await form.locator('[data-testid="submit"]').click();
  186 |   await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });
  187 | 
  188 |   const mail = await findMessage(
  189 |     {
  190 |       to: TESTCLUBADMIN_EMAIL,
  191 |       subjectMatches: /Passwort-?Reset/i,
  192 |       createdAfter: submittedAt,
  193 |     },
  194 |     { timeout: 10_000 },
  195 |   );
  196 |   const body = mail.Text + '\n' + mail.HTML;
  197 |   const linkMatch = body.match(/(https?:\/\/[^\s"'<>]+\/#\/confirm\?userid=[^\s"'<>]+)/i);
  198 |   expect(linkMatch, `confirmation link not found in reset email body: ${body.slice(0, 400)}`).not.toBeNull();
  199 |   const link = linkMatch![1];
  200 | 
  201 |   const hashIdx = link.indexOf('#/');
  202 |   expect(hashIdx).toBeGreaterThan(-1);
  203 |   const hashPath = link.slice(hashIdx);
  204 |   await page.goto(`${WEB_BASE}/${hashPath}`);
  205 | 
  206 |   await expect(page.locator('[data-testid="confirm-email-form"]')).toBeVisible({ timeout: 10_000 });
  207 |   await expect(page.locator('input#newPassword')).toBeVisible();
  208 |   await expect(page.locator('input#newPasswordConfirm')).toBeVisible();
  209 |   await screenshot(page, 'registration-flows-04');
  210 | });
  211 | 
```