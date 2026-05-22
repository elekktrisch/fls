// Reservations — week-view calendar. Day pages (1 day, all aircraft × hour cols)
// and a 7-day glance. Mobile collapses to single-day list.

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19];
const DAYS = [
  { d: 21, name: 'Thu' },
  { d: 22, name: 'Fri' },
  { d: 23, name: 'Sat' },
  { d: 24, name: 'Sun' },
  { d: 25, name: 'Mon' },
  { d: 26, name: 'Tue' },
  { d: 27, name: 'Wed' },
];

function ResvBlock({ r, isMaintenance, onClick }) {
  const leftPct = ((r.startH - HOURS[0]) / HOURS.length) * 100;
  const widthPct = (r.durH / HOURS.length) * 100;
  return (
    <button type="button" onClick={() => onClick?.(r)}
            style={{
              position: 'absolute',
              left: `calc(${leftPct}% + 2px)`,
              width: `calc(${widthPct}% - 4px)`,
              top: 4, bottom: 4,
              background: isMaintenance ? 'repeating-linear-gradient(135deg, var(--slate-200) 0 6px, var(--slate-100) 6px 12px)' : 'var(--color-accent-bg)',
              borderLeft: '3px solid ' + (isMaintenance ? 'var(--slate-400)' : 'var(--color-accent)'),
              padding: '4px 8px',
              fontFamily: 'inherit',
              fontSize: 12,
              color: isMaintenance ? 'var(--slate-700)' : 'var(--brand-700)',
              textAlign: 'left',
              cursor: 'pointer',
              overflow: 'hidden',
              borderRight: 0, borderTop: 0, borderBottom: 0,
              display: 'flex', flexDirection: 'column', justifyContent: 'center',
              gap: 1,
            }}>
      <div style={{ fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {r.pilot}
      </div>
      <div className="tabular" style={{ fontSize: 11, opacity: 0.8 }}>
        {String(r.startH).padStart(2, '0')}:00 – {String(Math.floor(r.startH + r.durH)).padStart(2, '0')}:{r.durH % 1 ? '30' : '00'}
        {r.type && <span style={{ marginLeft: 6, opacity: 0.7 }}>· {r.type}</span>}
      </div>
    </button>
  );
}

function Reservations() {
  const [day, setDay] = React.useState(21);
  const [view, setView] = React.useState('day'); // day | week

  const dayResv = RESERVATIONS.filter((r) => r.day === day);
  const dayLabel = `${DAYS.find((d) => d.d === day)?.name || ''} 21 May 2026`;

  return (
    <div>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Reservations</h1>
          <p className="af-page__sub">Week 21 · 21–27 May 2026 · LSZF</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn">
            <Icon name="calendar" size={16} />
            Today
          </button>
          <button className="af-btn" data-variant="primary">
            <Icon name="plus" size={16} />
            New reservation
          </button>
        </div>
      </div>

      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        marginBottom: 'var(--s-4)', gap: 'var(--s-3)', flexWrap: 'wrap',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s-1)' }}>
          <button className="af-iconbtn" aria-label="Previous week"><Icon name="chevLeft" size={16} /></button>
          <div style={{ display: 'flex', gap: 0, border: '1px solid var(--color-border-strong)', marginLeft: 'var(--s-2)' }}>
            {DAYS.map((d) => (
              <button key={d.d}
                      onClick={() => setDay(d.d)}
                      style={{
                        background: d.d === day ? 'var(--color-fg)' : 'var(--color-bg)',
                        color: d.d === day ? 'var(--color-bg)' : 'var(--color-fg-muted)',
                        border: 0,
                        borderLeft: d !== DAYS[0] ? '1px solid var(--color-border-strong)' : 0,
                        padding: '6px 12px',
                        minWidth: 56,
                        cursor: 'pointer',
                        fontFamily: 'inherit', fontSize: 12, fontWeight: 500,
                        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                      }}>
                <span style={{ opacity: 0.7, fontSize: 10, letterSpacing: '0.06em', textTransform: 'uppercase' }}>{d.name}</span>
                <span className="tabular" style={{ fontSize: 14 }}>{d.d}</span>
              </button>
            ))}
          </div>
          <button className="af-iconbtn" aria-label="Next week" style={{ marginLeft: 'var(--s-2)' }}><Icon name="chevRight" size={16} /></button>
        </div>
        <div style={{ display: 'flex', gap: 0, border: '1px solid var(--color-border-strong)' }}>
          {['day', 'week'].map((v) => (
            <button key={v}
                    onClick={() => setView(v)}
                    style={{
                      background: view === v ? 'var(--color-fg)' : 'var(--color-bg)',
                      color: view === v ? 'var(--color-bg)' : 'var(--color-fg-muted)',
                      border: 0, borderLeft: v !== 'day' ? '1px solid var(--color-border-strong)' : 0,
                      padding: '6px 14px',
                      cursor: 'pointer',
                      fontFamily: 'inherit', fontSize: 12, fontWeight: 500, textTransform: 'capitalize',
                    }}>{v}</button>
          ))}
        </div>
      </div>

      {/* weather strip */}
      <div style={{
        display: 'flex', gap: 'var(--s-4)', padding: 'var(--s-3) var(--s-4)',
        border: '1px solid var(--color-border)',
        background: 'var(--color-bg-subtle)',
        marginBottom: 'var(--s-4)',
        alignItems: 'center', flexWrap: 'wrap',
      }}>
        <span className="caps muted">METAR LSZF · 06:50Z</span>
        <span className="tabular mono" style={{ fontSize: 12 }}>VRB02KT 9999 FEW040 14/09 Q1019 NOSIG</span>
        <span style={{ flex: 1 }} />
        <span className="af-tag" data-tone="ok"><span className="af-dot" />VFR</span>
        <span className="muted" style={{ fontSize: 12 }}><Icon name="wind" size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />2 kt variable</span>
      </div>

      {view === 'day' ? (
        <div className="af-table-wrap rsv-day">
          <div className="af-table-tools">
            <span style={{ fontWeight: 500 }}>{dayLabel}</span>
            <span className="muted tabular" style={{ fontSize: 12 }}>{dayResv.length} reservations · {HOURS[0]}:00–{HOURS[HOURS.length - 1] + 1}:00 local</span>
          </div>

          {/* hour header */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: `140px repeat(${HOURS.length}, 1fr)`,
            borderBottom: '1px solid var(--color-border)',
            background: 'var(--color-bg-subtle)',
          }}>
            <div></div>
            {HOURS.map((h) => (
              <div key={h} style={{
                padding: '6px 0',
                fontSize: 11,
                color: 'var(--color-fg-muted)',
                fontVariantNumeric: 'tabular-nums',
                textAlign: 'left',
                paddingLeft: 6,
                borderLeft: '1px solid var(--color-border)',
              }}>
                {String(h).padStart(2, '0')}
              </div>
            ))}
          </div>

          {/* rows */}
          {FLEET.map((a) => {
            const rows = dayResv.filter((r) => r.acft === a.reg);
            return (
              <div key={a.reg} style={{
                display: 'grid',
                gridTemplateColumns: `140px 1fr`,
                borderBottom: '1px solid var(--color-border)',
                minHeight: 56,
              }}>
                <div style={{
                  padding: 'var(--s-2) var(--s-3)',
                  borderRight: '1px solid var(--color-border)',
                  display: 'flex', flexDirection: 'column', justifyContent: 'center',
                }}>
                  <div className="tabular" style={{ fontWeight: 500, fontSize: 13 }}>{a.reg}</div>
                  <div className="muted" style={{ fontSize: 11 }}>{a.type}</div>
                  {a.status !== 'available' && (
                    <span className="af-tag" data-tone="warn" style={{ marginTop: 4, alignSelf: 'flex-start' }}>
                      {a.status}
                    </span>
                  )}
                </div>
                <div style={{ position: 'relative' }}>
                  {/* hour grid lines */}
                  <div style={{
                    position: 'absolute', inset: 0,
                    display: 'grid', gridTemplateColumns: `repeat(${HOURS.length}, 1fr)`,
                    pointerEvents: 'none',
                  }}>
                    {HOURS.map((h) => (
                      <div key={h} style={{ borderLeft: '1px solid var(--color-border)' }} />
                    ))}
                  </div>
                  {rows.map((r) => (
                    <ResvBlock key={r.id} r={r}
                               isMaintenance={r.type === 'Maintenance'} />
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        // ─── Week view: aircraft × day matrix ───
        <div className="af-table-wrap">
          <div style={{
            display: 'grid',
            gridTemplateColumns: `140px repeat(${DAYS.length}, 1fr)`,
            background: 'var(--color-bg-subtle)',
            borderBottom: '1px solid var(--color-border)',
          }}>
            <div style={{ padding: '10px var(--s-3)' }} className="caps muted">Aircraft</div>
            {DAYS.map((d) => (
              <div key={d.d} style={{
                padding: '10px var(--s-2)',
                borderLeft: '1px solid var(--color-border)',
                fontSize: 12, fontWeight: 500,
                display: 'flex', alignItems: 'baseline', gap: 6,
              }}>
                <span className="caps muted">{d.name}</span>
                <span className="tabular">{d.d}</span>
              </div>
            ))}
          </div>
          {FLEET.map((a) => (
            <div key={a.reg} style={{
              display: 'grid',
              gridTemplateColumns: `140px repeat(${DAYS.length}, 1fr)`,
              borderBottom: '1px solid var(--color-border)',
              minHeight: 48,
            }}>
              <div style={{ padding: 'var(--s-2) var(--s-3)', borderRight: '1px solid var(--color-border)' }}>
                <div className="tabular" style={{ fontWeight: 500, fontSize: 13 }}>{a.reg}</div>
                <div className="muted" style={{ fontSize: 11 }}>{a.type.split(' ')[0]}</div>
              </div>
              {DAYS.map((d) => {
                const rs = RESERVATIONS.filter((r) => r.acft === a.reg && r.day === d.d);
                const totalH = rs.reduce((acc, r) => acc + r.durH, 0);
                const pct = Math.min(100, (totalH / HOURS.length) * 100);
                return (
                  <div key={d.d} style={{
                    padding: 'var(--s-2)',
                    borderLeft: '1px solid var(--color-border)',
                    display: 'flex', flexDirection: 'column', gap: 3,
                    background: d.d === day ? 'var(--color-bg-subtle)' : 'transparent',
                  }}>
                    {rs.length > 0 ? (
                      <>
                        <div className="tabular" style={{ fontSize: 12, fontWeight: 500 }}>
                          {rs.length} · {totalH.toFixed(1)}h
                        </div>
                        <div className="af-progress" style={{ height: 3 }}>
                          <div className="af-progress__bar" style={{ width: pct + '%' }} />
                        </div>
                        <div className="muted" style={{ fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {rs[0].pilot}{rs.length > 1 ? ` +${rs.length - 1}` : ''}
                        </div>
                      </>
                    ) : (
                      <span className="subtle" style={{ fontSize: 11 }}>—</span>
                    )}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      )}

      <style>{`
        @media (max-width: 767px) {
          .rsv-day { overflow-x: auto; }
          .rsv-day > div:not(.af-table-tools) { min-width: 720px; }
        }
      `}</style>
    </div>
  );
}

Object.assign(window, { Reservations });
