// Public landing screen — splash-led, login CTA, terse copy.
// Outside the app shell — its own chrome.

function Landing({ onSignIn }) {
  return (
    <div style={{
      minHeight: '100vh',
      background: 'var(--color-bg)',
      display: 'flex', flexDirection: 'column',
    }}>
      <header style={{
        height: 'var(--topbar-h)',
        padding: '0 var(--s-6)',
        display: 'flex', alignItems: 'center',
        borderBottom: '1px solid var(--color-border)',
      }}>
        <Wordmark />
        <div style={{ flex: 1 }} />
        <button className="af-btn" data-size="sm" onClick={onSignIn}>
          Sign in
        </button>
      </header>

      <section style={{
        flex: 1,
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.1fr)',
        gap: 0,
        alignItems: 'stretch',
      }} className="lp-hero">
        <div style={{
          padding: 'var(--s-12) var(--s-7)',
          display: 'flex', flexDirection: 'column',
          justifyContent: 'center',
          maxWidth: 560,
          marginLeft: 'auto', width: '100%',
        }}>
          <p className="caps muted" style={{ marginBottom: 'var(--s-4)' }}>
            Club operations · Switzerland
          </p>
          <h1 style={{ fontSize: 'var(--fs-display)', lineHeight: 1.15, marginBottom: 'var(--s-4)', textWrap: 'pretty' }}>
            Log flights. Reserve aircraft. Run the airfield.
          </h1>
          <p style={{ color: 'var(--color-fg-muted)', fontSize: 16, lineHeight: 1.5, marginBottom: 'var(--s-6)', maxWidth: 480 }}>
            Built for flying clubs that already exist and already fly. One quiet tool for the logbook, the reservation calendar, and the member roster.
          </p>
          <div style={{ display: 'flex', gap: 'var(--s-3)' }}>
            <button className="af-btn" data-variant="primary" data-size="lg" onClick={onSignIn}>
              Sign in
              <Icon name="arrowRight" size={16} />
            </button>
            <button className="af-btn" data-size="lg">
              Request access
            </button>
          </div>

          <div style={{
            marginTop: 'var(--s-10)',
            paddingTop: 'var(--s-5)',
            borderTop: '1px solid var(--color-border)',
            display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)',
            gap: 'var(--s-5)',
          }}>
            {[
              { k: '34', l: 'clubs' },
              { k: '1 870', l: 'pilots' },
              { k: '184 002', l: 'flights logged' },
            ].map((s) => (
              <div key={s.l}>
                <div className="tabular" style={{ fontSize: 22, fontWeight: 500 }}>{s.k}</div>
                <div className="muted" style={{ fontSize: 12 }}>{s.l}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Splash placeholder. ADR-0014: landing splash is per-tenant. */}
        <div style={{
          background: `
            linear-gradient(135deg, rgba(30, 91, 184, 0.06) 0%, rgba(30, 91, 184, 0.02) 60%),
            repeating-linear-gradient(45deg, var(--slate-100) 0 8px, var(--slate-50) 8px 16px)
          `,
          borderLeft: '1px solid var(--color-border)',
          position: 'relative',
          minHeight: 480,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{
            position: 'absolute', top: 'var(--s-4)', left: 'var(--s-4)',
            fontFamily: 'var(--font-mono)', fontSize: 11,
            color: 'var(--color-fg-subtle)',
            padding: '4px 8px',
            border: '1px solid var(--color-border)',
            background: 'var(--color-bg)',
          }}>
            splash · per-tenant · 1600×1200
          </div>
          <div style={{
            textAlign: 'center', color: 'var(--color-fg-subtle)',
            fontFamily: 'var(--font-mono)', fontSize: 12,
          }}>
            <Icon name="mapPin" size={32} />
            <div style={{ marginTop: 'var(--s-2)' }}>airfield photography</div>
          </div>
          <div style={{
            position: 'absolute', bottom: 'var(--s-4)', right: 'var(--s-4)',
            fontFamily: 'var(--font-mono)', fontSize: 11,
            color: 'var(--color-fg-subtle)',
          }}>
            LSZF · 47.4435°N 8.2356°E
          </div>
        </div>
      </section>

      <footer style={{
        borderTop: '1px solid var(--color-border)',
        padding: 'var(--s-4) var(--s-6)',
        display: 'flex', justifyContent: 'space-between',
        fontSize: 12, color: 'var(--color-fg-muted)', flexWrap: 'wrap', gap: 'var(--s-3)',
      }}>
        <span>© 2026 AlpenFlight</span>
        <span style={{ display: 'flex', gap: 'var(--s-4)' }}>
          <a href="#">Status</a>
          <a href="#">Documentation</a>
          <a href="#">Imprint</a>
        </span>
      </footer>

      <style>{`
        @media (max-width: 767px) {
          .lp-hero { grid-template-columns: 1fr !important; }
          .lp-hero > div:last-child { display: none; }
        }
      `}</style>
    </div>
  );
}

// ── Login ──────────────────────────────────────────────────
function Login({ onSignIn, onBack }) {
  const [email, setEmail] = React.useState('m.weber@scbirrfeld.ch');
  const [password, setPassword] = React.useState('••••••••••');
  const [error, setError] = React.useState('');
  const submit = (e) => {
    e.preventDefault();
    if (!email.includes('@')) { setError('Enter a valid email.'); return; }
    onSignIn();
  };
  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'var(--color-bg-subtle)',
      padding: 'var(--s-6)',
    }}>
      <div className="af-card" style={{ width: '100%', maxWidth: 420 }}>
        <div className="af-card__hd" style={{ padding: 'var(--s-5) var(--s-5) var(--s-4)', borderBottom: 0 }}>
          <div>
            <Wordmark />
            <h2 style={{ marginTop: 'var(--s-4)', fontSize: 18 }}>Sign in</h2>
            <p className="muted" style={{ fontSize: 13, marginTop: 4 }}>Members and staff only.</p>
          </div>
        </div>
        <form className="af-card__bd" onSubmit={submit} style={{ padding: '0 var(--s-5) var(--s-5)', display: 'flex', flexDirection: 'column', gap: 'var(--s-4)' }}>
          {/* External IDPs first — most members reuse a personal account. */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--s-2)' }}>
            <button type="button" className="af-btn" data-size="lg" style={{ justifyContent: 'flex-start', gap: 'var(--s-3)' }}
                    onClick={onSignIn}>
              <span className="idp-glyph" aria-hidden="true">G</span>
              Continue with Google
            </button>
            <button type="button" className="af-btn" data-size="lg" style={{ justifyContent: 'flex-start', gap: 'var(--s-3)' }}
                    onClick={onSignIn}>
              <span className="idp-glyph" aria-hidden="true">A</span>
              Continue with Apple
            </button>
            <button type="button" className="af-btn" data-size="lg" style={{ justifyContent: 'flex-start', gap: 'var(--s-3)' }}
                    onClick={onSignIn}>
              <span className="idp-glyph" aria-hidden="true">SP</span>
              Continue with SwissPass SSO
            </button>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s-3)', color: 'var(--color-fg-subtle)', fontSize: 11, textTransform: 'uppercase', letterSpacing: 'var(--tracking-caps)' }}>
            <span style={{ flex: 1, height: 1, background: 'var(--color-border)' }} />
            <span style={{ whiteSpace: 'nowrap' }}>or with email</span>
            <span style={{ flex: 1, height: 1, background: 'var(--color-border)' }} />
          </div>

          <style>{`
            .idp-glyph {
              display: inline-flex; align-items: center; justify-content: center;
              width: 22px; height: 22px;
              border: 1px solid var(--color-border-strong);
              background: var(--color-bg);
              color: var(--color-fg);
              font-family: var(--font-sans);
              font-size: 11px; font-weight: 600;
              flex-shrink: 0;
            }
          `}</style>

          <div className="af-field" data-required>
            <label className="af-field__label" htmlFor="li-email">Email</label>
            <input id="li-email" className="af-input" type="email"
                   value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div className="af-field" data-required>
            <label className="af-field__label" htmlFor="li-pw">Password</label>
            <input id="li-pw" className="af-input" type="password"
                   value={password} onChange={(e) => setPassword(e.target.value)} />
            <a href="#" style={{ fontSize: 12 }}>Forgot password</a>
          </div>
          {error && <div className="af-field__error">{error}</div>}
          <button type="submit" className="af-btn" data-variant="primary" data-size="lg">
            Sign in
          </button>
          <button type="button" className="af-btn" data-variant="ghost" onClick={onBack}>
            Back
          </button>
        </form>
      </div>
    </div>
  );
}

Object.assign(window, { Landing, Login });
