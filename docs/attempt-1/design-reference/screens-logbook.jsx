// Logbook screen — recent flights table with density tweak.

function StatusTag({ status }) {
  const map = {
    open:      { tone: 'warn',   label: 'Open' },
    submitted: { tone: 'brand',  label: 'Submitted' },
    approved:  { tone: 'ok',     label: 'Approved' },
    rejected:  { tone: 'danger', label: 'Rejected' },
  };
  const cfg = map[status] || { tone: '', label: status };
  return (
    <span className="af-tag" data-tone={cfg.tone}>
      <span className="af-dot" />{cfg.label}
    </span>
  );
}

function Logbook({ onLogFlight, onOpenFlight, density }) {
  const [filter, setFilter] = React.useState('all');
  const [query, setQuery] = React.useState('');
  const [selected, setSelected] = React.useState(null);

  const rows = FLIGHTS.filter((f) => {
    if (filter !== 'all' && f.status !== filter) return false;
    if (query) {
      const q = query.toLowerCase();
      return [f.id, f.pic, f.acft, f.dep, f.arr, f.remarks].some((v) => String(v).toLowerCase().includes(q));
    }
    return true;
  });

  const totalBlock = FLIGHTS.reduce((acc, f) => {
    const [h, m] = f.block.split(':').map(Number);
    return acc + h * 60 + m;
  }, 0);
  const totalH = Math.floor(totalBlock / 60), totalM = totalBlock % 60;

  return (
    <div data-density={density}>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Logbook</h1>
          <p className="af-page__sub">May 2026 · {FLIGHTS.length} flights · {totalH}h {String(totalM).padStart(2, '0')}m total block time</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn">
            <Icon name="download" size={16} />
            Export
          </button>
          <button className="af-btn" data-variant="primary" onClick={onLogFlight}>
            <Icon name="plus" size={16} />
            Log flight
          </button>
        </div>
      </div>

      {/* stats strip */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)',
        gap: 0, marginBottom: 'var(--s-5)',
        border: '1px solid var(--color-border)',
      }} className="logbook-stats">
        {[
          { l: 'Flights this month', v: '24', d: '↑ 3 vs. April' },
          { l: 'Block hours',        v: '38:42', d: 'tabular' },
          { l: 'Pending approval',   v: '3',     d: '2 over 48h' },
          { l: 'Fleet utilisation',  v: '64%',   d: '4 of 5 aircraft' },
        ].map((s, i) => (
          <div key={s.l} style={{
            padding: 'var(--s-4)',
            borderLeft: i > 0 ? '1px solid var(--color-border)' : 0,
          }}>
            <div className="caps muted" style={{ marginBottom: 4 }}>{s.l}</div>
            <div className="tabular" style={{ fontSize: 24, fontWeight: 500, lineHeight: 1.2 }}>{s.v}</div>
            <div className="muted" style={{ fontSize: 12 }}>{s.d}</div>
          </div>
        ))}
      </div>

      <div className="af-table-wrap">
        <div className="af-table-tools">
          <div style={{ display: 'flex', gap: 'var(--s-2)', alignItems: 'center' }}>
            <div style={{ position: 'relative' }}>
              <Icon name="search" size={14}
                    style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--color-fg-subtle)' }} />
              <input className="af-input" placeholder="Search flights"
                     value={query} onChange={(e) => setQuery(e.target.value)}
                     style={{ paddingLeft: 32, width: 220, height: 32, fontSize: 13 }} />
            </div>
            <div role="tablist" style={{ display: 'flex', gap: 0, border: '1px solid var(--color-border-strong)' }}>
              {['all', 'open', 'submitted', 'approved'].map((f) => (
                <button key={f} type="button"
                        onClick={() => setFilter(f)}
                        style={{
                          background: filter === f ? 'var(--color-fg)' : 'var(--color-bg)',
                          color: filter === f ? 'var(--color-bg)' : 'var(--color-fg-muted)',
                          border: 0,
                          padding: '0 var(--s-3)', height: 30,
                          fontSize: 12, fontWeight: 500,
                          textTransform: 'capitalize',
                          borderLeft: f !== 'all' ? '1px solid var(--color-border-strong)' : 0,
                          cursor: 'pointer',
                          fontFamily: 'inherit',
                        }}>
                  {f}
                </button>
              ))}
            </div>
          </div>
          <div style={{ display: 'flex', gap: 'var(--s-2)' }}>
            <button className="af-btn" data-size="sm">
              <Icon name="filter" size={14} />
              Filter
            </button>
          </div>
        </div>

        {/* Flight list — card per flight, all viewports. */}
        <ul className="af-flightlist" role="list">
          {rows.map((f) => (
            <li key={f.id}
                className="af-flightcard"
                data-selected={selected === f.id}
                onClick={() => { setSelected(f.id); onOpenFlight?.(f); }}>
              <div className="af-flightcard__hd">
                <span className="tabular" style={{ fontWeight: 500 }}>{f.date.slice(5)}</span>
                <span className="tabular mono muted" style={{ fontSize: 11 }}>{f.id}</span>
                <span className="af-tag" data-tone="" style={{ background: 'var(--slate-100)', color: 'var(--slate-700)' }}>{f.type}</span>
                <span style={{ flex: 1 }} />
                <StatusTag status={f.status} />
                <button className="af-iconbtn" aria-label="More" onClick={(e) => e.stopPropagation()}>
                  <Icon name="more" size={16} />
                </button>
              </div>
              <div className="af-flightcard__route">
                <span className="tabular" style={{ fontSize: 20, fontWeight: 500 }}>{f.dep}</span>
                <Icon name="arrowRight" size={14} style={{ color: 'var(--color-fg-subtle)' }} />
                <span className="tabular" style={{ fontSize: 20, fontWeight: 500 }}>{f.arr}</span>
                <span style={{ flex: 1 }} />
                <span className="caps muted">block</span>
                <span className="tabular" style={{ fontSize: 18, fontWeight: 500 }}>{f.block}</span>
              </div>
              <dl className="af-flightcard__grid">
                <div><dt>Aircraft</dt><dd className="tabular">{f.acft}</dd></div>
                <div><dt>PIC</dt><dd>{f.pic}</dd></div>
                <div><dt>Off block</dt><dd className="tabular">{f.off}</dd></div>
                <div><dt>On block</dt><dd className="tabular">{f.on}</dd></div>
                <div><dt>Landings</dt><dd className="tabular">{f.ldgs}</dd></div>
                <div className="af-flightcard__grid-remarks">
                  <dt>Remarks</dt>
                  <dd>{f.remarks || <span className="subtle">—</span>}</dd>
                </div>
              </dl>
            </li>
          ))}
        </ul>

        <div style={{
          padding: 'var(--s-3) var(--s-4)',
          borderTop: '1px solid var(--color-border)',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          fontSize: 12, color: 'var(--color-fg-muted)',
        }}>
          <span className="tabular">Showing {rows.length} of {FLIGHTS.length}</span>
          <div style={{ display: 'flex', gap: 'var(--s-1)' }}>
            <button className="af-iconbtn" disabled aria-label="Previous"><Icon name="chevLeft" size={14} /></button>
            <button className="af-iconbtn" disabled aria-label="Next"><Icon name="chevRight" size={14} /></button>
          </div>
        </div>
      </div>

      <style>{`
        @media (max-width: 767px) {
          .logbook-stats { grid-template-columns: repeat(2, 1fr) !important; }
          .logbook-stats > div:nth-child(3),
          .logbook-stats > div:nth-child(4) { border-top: 1px solid var(--color-border); }
          .logbook-stats > div:nth-child(3) { border-left: 0 !important; }
        }
      `}</style>
    </div>
  );
}

Object.assign(window, { Logbook, StatusTag });
