// Home — landing page after sign-in.
// Current user is M. Weber. "Last flight" finds the most recent FLIGHT entry
// where M. Weber appears in any role (PIC, co-pilot, tow pilot, passenger).

const CURRENT_USER = 'M. Weber';

function findLastFlight(user) {
  // FLIGHTS in this prototype only tracks `pic`. In a real impl this would
  // also look at copilot / towPic / pax fields. For now: latest by date+id
  // matching pic === user.
  const matches = FLIGHTS
    .filter((f) => f.pic === user)
    .sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id));
  return matches[0] || FLIGHTS[0];
}

function findNextReservation(user) {
  return RESERVATIONS.find((r) => r.pilot === user) || null;
}

function StatTile({ label, value, sub, accent }) {
  return (
    <div style={{
      padding: 'var(--s-4)',
      borderLeft: accent ? '3px solid var(--color-accent)' : '0',
    }}>
      <div className="caps muted" style={{ marginBottom: 6 }}>{label}</div>
      <div className="tabular" style={{ fontSize: 26, fontWeight: 500, lineHeight: 1.1 }}>{value}</div>
      <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>{sub}</div>
    </div>
  );
}

function Home({ onLogFlight, onOpenLogbook, onOpenReservations, onOpenStats, onOpenFlight }) {
  const lastFlight = findLastFlight(CURRENT_USER);
  const nextResv   = findNextReservation(CURRENT_USER);
  const acft = FLEET.find((a) => a.reg === lastFlight.acft);

  return (
    <>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Good morning, {CURRENT_USER}</h1>
          <p className="af-page__sub">Thursday 21 May 2026 · LSZF · METAR VRB02KT 9999 FEW040 14/09</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn" onClick={onOpenLogbook}>
            Open logbook
          </button>
          <button className="af-btn" data-variant="primary" onClick={onLogFlight}>
            <Icon name="plus" size={16} />Log flight
          </button>
        </div>
      </div>

      {/* Top row: last flight + next reservation */}
      <div className="home-top">
        {/* Last flight — clickable card */}
        <button
          type="button"
          onClick={() => onOpenFlight?.(lastFlight)}
          className="home-card home-last"
          aria-label="Open last flight"
        >
          <div className="home-card__hd">
            <span className="caps muted">Your last flight</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <StatusTag status={lastFlight.status} />
              <Icon name="chevRight" size={14} style={{ color: 'var(--color-fg-subtle)' }} />
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--s-3)', marginTop: 'var(--s-3)' }}>
            <span className="tabular" style={{ fontSize: 32, fontWeight: 500, letterSpacing: '-0.01em' }}>{lastFlight.dep}</span>
            <Icon name="arrowRight" size={18} style={{ color: 'var(--color-fg-subtle)' }} />
            <span className="tabular" style={{ fontSize: 32, fontWeight: 500, letterSpacing: '-0.01em' }}>{lastFlight.arr}</span>
            <span style={{ flex: 1 }} />
            <div style={{ textAlign: 'right' }}>
              <div className="caps muted">block</div>
              <div className="tabular" style={{ fontSize: 22, fontWeight: 500 }}>{lastFlight.block}</div>
            </div>
          </div>
          <dl className="home-last__grid">
            <div><dt>Date</dt><dd className="tabular">{lastFlight.date}</dd></div>
            <div><dt>Aircraft</dt><dd className="tabular">{lastFlight.acft}<span className="muted"> · {acft?.type || '—'}</span></dd></div>
            <div><dt>Role</dt><dd>Pilot in command</dd></div>
            <div><dt>Off / On</dt><dd className="tabular">{lastFlight.off} – {lastFlight.on}</dd></div>
            <div><dt>Landings</dt><dd className="tabular">{lastFlight.ldgs}</dd></div>
            <div><dt>Type</dt><dd>{lastFlight.type}</dd></div>
          </dl>
          {lastFlight.remarks && (
            <p className="home-last__remarks">{lastFlight.remarks}</p>
          )}
        </button>

        {/* Side rail: next reservation + quick links */}
        <div className="home-side">
          <button
            type="button"
            onClick={onOpenReservations}
            className="home-card home-side__card"
            aria-label="Open reservations"
          >
            <div className="home-card__hd">
              <span className="caps muted">Next reservation</span>
              <Icon name="chevRight" size={14} style={{ color: 'var(--color-fg-subtle)' }} />
            </div>
            {nextResv ? (
              <>
                <div className="tabular" style={{ fontSize: 22, fontWeight: 500, marginTop: 'var(--s-3)' }}>
                  Today · {String(nextResv.startH).padStart(2, '0')}:00
                </div>
                <div className="muted tabular" style={{ fontSize: 13, marginTop: 4 }}>
                  {nextResv.durH}h · {nextResv.acft}
                </div>
                <div style={{ display: 'flex', gap: 6, marginTop: 'var(--s-3)' }}>
                  <span className="af-tag" data-tone="brand">{nextResv.type || 'Reservation'}</span>
                </div>
              </>
            ) : (
              <div className="muted" style={{ fontSize: 13, marginTop: 'var(--s-3)' }}>No upcoming reservation.</div>
            )}
          </button>

          <div className="home-card home-side__card" style={{ cursor: 'default' }}>
            <div className="home-card__hd">
              <span className="caps muted">License & medical</span>
            </div>
            <dl style={{
              margin: 'var(--s-3) 0 0',
              display: 'grid',
              gridTemplateColumns: 'auto 1fr',
              gap: 'var(--s-2) var(--s-3)',
              fontSize: 13,
            }}>
              <dt className="muted">PPL(A)</dt>
              <dd className="tabular" style={{ margin: 0, textAlign: 'right', whiteSpace: 'nowrap' }}>2028-04-30</dd>
              <dt className="muted">Medical 2</dt>
              <dd className="tabular" style={{ margin: 0, textAlign: 'right', whiteSpace: 'nowrap' }}>2027-03-12</dd>
              <dt className="muted">SEP rating</dt>
              <dd className="tabular" style={{ margin: 0, textAlign: 'right', whiteSpace: 'nowrap' }}>2027-09-30</dd>
            </dl>
          </div>
        </div>
      </div>

      {/* Stats overview */}
      <section className="home-stats">
        <header className="home-section__hd">
          <h2>Overview</h2>
          <button className="home-link" onClick={onOpenStats}>
            View full statistics
            <Icon name="arrowRight" size={14} />
          </button>
        </header>

        <div className="home-stats__row">
          <StatTile label="Your hours · month" value="8:42"  sub="6 flights"           accent />
          <StatTile label="Your hours · year"  value="74:18" sub="↑ 12% vs. 2025"      />
          <StatTile label="Club flights · month" value="24"  sub="↑ 3 vs. April"       />
          <StatTile label="Club block hours"   value="38:42" sub="May 2026"            />
          <StatTile label="Fleet utilisation"  value="64%"   sub="4 of 5 aircraft"     />
          <StatTile label="Pending approval"   value="3"     sub="2 over 48h"          />
        </div>
      </section>

      <style>{`
        .home-top {
          display: grid;
          grid-template-columns: 2fr 1fr;
          gap: var(--s-4);
          margin-bottom: var(--s-6);
        }
        .home-card {
          appearance: none;
          background: var(--color-bg);
          border: 1px solid var(--color-border);
          border-radius: 0;
          padding: var(--s-5);
          text-align: left;
          font: inherit;
          color: inherit;
          cursor: pointer;
          width: 100%;
          transition: border-color var(--motion-fast), background var(--motion-fast);
        }
        .home-card:hover {
          border-color: var(--color-border-strong);
          background: var(--color-bg-subtle);
        }
        .home-card:focus-visible {
          outline: 2px solid var(--color-accent);
          outline-offset: -1px;
        }
        .home-card__hd {
          display: flex; align-items: center; justify-content: space-between;
          gap: var(--s-3);
        }
        .home-last { display: flex; flex-direction: column; gap: var(--s-3); }
        .home-last__grid {
          display: grid;
          grid-template-columns: repeat(6, 1fr);
          gap: var(--s-3);
          margin: var(--s-4) 0 0;
          padding-top: var(--s-4);
          border-top: 1px solid var(--color-border);
        }
        .home-last__grid > div { min-width: 0; }
        .home-last__grid dt {
          font-size: 10px; font-weight: var(--fw-medium);
          letter-spacing: var(--tracking-caps); text-transform: uppercase;
          color: var(--color-fg-muted); margin-bottom: 2px;
        }
        .home-last__grid dd {
          margin: 0; font-size: var(--fs-sm);
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        .home-last__remarks {
          margin-top: var(--s-3);
          font-size: var(--fs-xs);
          color: var(--color-fg-muted);
          padding-top: var(--s-3);
          border-top: 1px dashed var(--color-border);
        }
        .home-side { display: flex; flex-direction: column; gap: var(--s-4); }
        .home-side__card { padding: var(--s-4); }
        .home-section__hd {
          display: flex; align-items: baseline; justify-content: space-between;
          gap: var(--s-3);
          margin-bottom: var(--s-3);
          padding-bottom: var(--s-2);
          border-bottom: 1px solid var(--color-border);
        }
        .home-section__hd h2 { font-size: var(--fs-md); font-weight: var(--fw-medium); }
        .home-link {
          appearance: none; background: none; border: 0; padding: 0;
          color: var(--color-accent); font: inherit; font-size: 13px;
          font-weight: var(--fw-medium); cursor: pointer;
          display: inline-flex; align-items: center; gap: 6px;
        }
        .home-link:hover { color: var(--color-accent-hover); }
        .home-stats__row {
          display: grid;
          grid-template-columns: repeat(6, 1fr);
          border: 1px solid var(--color-border);
          background: var(--color-bg);
        }
        .home-stats__row > div { border-left: 1px solid var(--color-border); }
        .home-stats__row > div:first-child { border-left: 0; }
        @media (max-width: 899px) {
          .home-top { grid-template-columns: 1fr; }
          .home-stats__row { grid-template-columns: repeat(3, 1fr); }
          .home-stats__row > div:nth-child(4) { border-left: 0; border-top: 1px solid var(--color-border); }
          .home-stats__row > div:nth-child(5),
          .home-stats__row > div:nth-child(6) { border-top: 1px solid var(--color-border); }
        }
        @media (max-width: 639px) {
          .home-last__grid { grid-template-columns: repeat(2, 1fr); }
          .home-stats__row { grid-template-columns: repeat(2, 1fr); }
          .home-stats__row > div:nth-child(odd) { border-left: 0; }
          .home-stats__row > div:nth-child(n+3) { border-top: 1px solid var(--color-border); }
        }
      `}</style>
    </>
  );
}

// ── Detail stats page (linked from home) ────────────────────
function Stats({ onBack }) {
  // small inline sparkline-y mark using a div bar; no extra deps.
  const months = ['Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr', 'May'];
  const hours  = [9.2, 11.4, 12.0, 8.8, 6.4, 3.2, 1.8, 2.6, 4.4, 6.8, 7.6, 8.7];
  const max    = Math.max(...hours);

  return (
    <>
      <div className="af-breadcrumb">
        <button onClick={onBack}>Home</button>
        <Icon name="chevRight" size={12} />
        <span style={{ color: 'var(--color-fg)' }}>Statistics</span>
      </div>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Statistics</h1>
          <p className="af-page__sub">Personal & club totals · rolling 12 months · UTC.</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn"><Icon name="download" size={16} />Export CSV</button>
        </div>
      </div>

      {/* Headline tiles */}
      <div className="home-stats__row" style={{ marginBottom: 'var(--s-6)' }}>
        <StatTile label="Your block · 12 mo"   value="83:14"  sub="61 flights"          accent />
        <StatTile label="Your block · YTD"     value="34:42"  sub="22 flights"          />
        <StatTile label="Your landings · YTD"  value="48"     sub="full-stop"           />
        <StatTile label="Hours to revalidation" value="4:18"   sub="SEP rating · 2027-09-30" />
        <StatTile label="Club block · 12 mo"   value="612:08" sub="437 flights"         />
        <StatTile label="Club landings · 12 mo" value="1 204" sub="all aircraft"        />
      </div>

      {/* Monthly bar chart */}
      <section className="af-card" style={{ marginBottom: 'var(--s-6)' }}>
        <div className="af-card__hd">
          <h3>Monthly block hours · you</h3>
          <span className="muted" style={{ fontSize: 12 }}>last 12 months · {hours.reduce((a, b) => a + b, 0).toFixed(1)} h total</span>
        </div>
        <div className="af-card__bd">
          <div style={{
            display: 'grid',
            gridTemplateColumns: `repeat(${months.length}, 1fr)`,
            gap: 'var(--s-2)',
            alignItems: 'end',
            height: 180,
          }}>
            {hours.map((h, i) => (
              <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, height: '100%' }}>
                <div style={{ flex: 1, display: 'flex', alignItems: 'flex-end', width: '100%' }}>
                  <div style={{
                    width: '100%',
                    height: `${(h / max) * 100}%`,
                    background: i === hours.length - 1 ? 'var(--color-accent)' : 'var(--slate-300)',
                  }} />
                </div>
                <div className="tabular" style={{ fontSize: 11, color: 'var(--color-fg-muted)' }}>{months[i]}</div>
                <div className="tabular" style={{ fontSize: 11 }}>{h.toFixed(1)}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* By aircraft */}
      <section className="af-card">
        <div className="af-card__hd">
          <h3>By aircraft · your hours</h3>
          <span className="muted" style={{ fontSize: 12 }}>YTD 2026</span>
        </div>
        <div className="af-card__bd" style={{ padding: 0 }}>
          {[
            { reg: 'HB-EAB', type: 'Robin DR400-180', h: 12.3, f: 8 },
            { reg: 'HB-3215', type: 'ASK 21',         h: 14.2, f: 9 },
            { reg: 'HB-3098', type: 'Duo Discus XL',  h: 5.1,  f: 3 },
            { reg: 'HB-EDC', type: 'Piper PA-25-235', h: 2.4,  f: 2 },
            { reg: 'HB-SNJ', type: 'Cessna 172 N',    h: 0.7,  f: 1 },
          ].map((row, i) => {
            const pct = (row.h / 14.2) * 100;
            return (
              <div key={row.reg} style={{
                display: 'grid',
                gridTemplateColumns: '100px 200px 1fr 80px 60px',
                gap: 'var(--s-3)',
                alignItems: 'center',
                padding: 'var(--s-3) var(--s-4)',
                borderTop: i === 0 ? '0' : '1px solid var(--color-border)',
              }}>
                <span className="tabular" style={{ fontWeight: 500 }}>{row.reg}</span>
                <span className="muted">{row.type}</span>
                <div className="af-progress" style={{ height: 6 }}>
                  <div className="af-progress__bar" style={{ width: pct + '%' }} />
                </div>
                <span className="tabular" style={{ textAlign: 'right' }}>{row.h.toFixed(1)} h</span>
                <span className="tabular muted" style={{ textAlign: 'right' }}>{row.f} fl</span>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}

Object.assign(window, { Home, Stats });
