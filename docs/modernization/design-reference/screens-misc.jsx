// Aircraft + Members — terse list screens reachable from nav.

function Aircraft() {
  return (
    <>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Aircraft</h1>
          <p className="af-page__sub">{FLEET.length} aircraft on register</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn" data-variant="primary">
            <Icon name="plus" size={16} />Add aircraft
          </button>
        </div>
      </div>
      <div className="af-table-wrap">
        <table className="af-table">
          <thead>
            <tr>
              <th>Reg.</th><th>Type</th>
              <th className="tabular num">Engine h</th>
              <th>Next 100h check</th>
              <th>Insurance</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {FLEET.map((a) => (
              <tr key={a.reg}>
                <td className="tabular" style={{ fontWeight: 500 }}>{a.reg}</td>
                <td>{a.type}</td>
                <td className="tabular num">{a.engineH.toFixed(1)}</td>
                <td className="tabular muted">
                  {a.reg === 'HB-CXY' ? 'overdue' : `in ${Math.round(100 - (a.engineH % 100))}h`}
                </td>
                <td className="tabular muted">2026-12-31</td>
                <td>
                  {a.status === 'maintenance'
                    ? <span className="af-tag" data-tone="warn"><span className="af-dot" />maintenance</span>
                    : <span className="af-tag" data-tone="ok"><span className="af-dot" />available</span>}
                </td>
                <td>
                  <button className="af-iconbtn"><Icon name="chevRight" size={14} /></button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Members() {
  return (
    <>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Members</h1>
          <p className="af-page__sub">{PILOTS.length} active members</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn"><Icon name="download" size={16} />Export</button>
          <button className="af-btn" data-variant="primary"><Icon name="plus" size={16} />Add member</button>
        </div>
      </div>
      <div className="af-table-wrap">
        <table className="af-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>License</th>
              <th className="num tabular">Medical</th>
              <th className="num tabular">PPL hours</th>
              <th>Role</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {PILOTS.map((p, i) => (
              <tr key={p}>
                <td style={{ fontWeight: 500 }}>{p}</td>
                <td className="tabular">{['PPL(A)', 'PPL(A) + NIGHT', 'CPL(A)', 'PPL(A)', 'PPL(A) + IR', 'STUDENT', 'PPL(A)', 'CPL(A)', 'PPL(A)'][i]}</td>
                <td className="num tabular muted">{['2027-03', '2026-11', '2026-09', '2027-02', '2026-08', '2027-04', '2027-01', '2026-10', '2027-05'][i]}</td>
                <td className="num tabular">{[412, 1840, 3120, 187, 2204, 28, 945, 2680, 1402][i]}</td>
                <td className="muted">{['member', 'instructor', 'instructor', 'student', 'member', 'student', 'member', 'staff', 'member'][i]}</td>
                <td>
                  {i === 5
                    ? <span className="af-tag" data-tone="warn"><span className="af-dot" />pending</span>
                    : <span className="af-tag" data-tone="ok"><span className="af-dot" />active</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

Object.assign(window, { Aircraft, Members });
