// Flight entry — glider club hot path. Single layout: 3-step wizard
//   1. Launch    — date, airfield, launch method, release altitude
//   2. Glider    — glider, crew, take-off/landing, landings, remarks
//   3. Tow plane — tow plane, tow pilot, release time, landing, fuel
//                  (skipped/empty when launch is Winch or Self-launch)

function blockBetween(off, on) {
  if (!/^\d{2}:\d{2}$/.test(off) || !/^\d{2}:\d{2}$/.test(on)) return '';
  const [oh, om] = off.split(':').map(Number);
  const [nh, nm] = on.split(':').map(Number);
  let mins = nh * 60 + nm - (oh * 60 + om);
  if (mins < 0) mins += 24 * 60;
  return `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
}

const INITIAL = {
  date: '2026-05-21',
  airfield: 'LSZF',
  launchType: 'Aerotow',
  releaseAlt: 600,
  releaseTime: '10:32',
  // glider
  glider: 'HB-3215',
  gliderPic: 'S. Aebi',
  gliderPax: 'D. Roth',
  gliderOff: '10:24',
  gliderOn: '11:42',
  gliderLdgs: 1,
  gliderType: 'Training',
  gliderRemarks: 'Local soaring, Lägern ridge',
  // tow
  towAcft: 'HB-EAB',
  towPic: 'M. Weber',
  towOff: '10:24',
  towOn: '10:39',
  fuelStart: 110,
  fuelEnd: 78,
};

function useFlight() {
  const [state, setState] = React.useState(INITIAL);
  const set = (k, v) => setState((s) => {
    // keep tow take-off in sync with glider take-off
    if (k === 'gliderOff') return { ...s, gliderOff: v, towOff: v };
    return { ...s, [k]: v };
  });
  return { state, set,
    gliderBlock: blockBetween(state.gliderOff, state.gliderOn),
    towBlock:    blockBetween(state.towOff, state.towOn),
  };
}

function Field({ label, required, hint, error, children, span = 1, style }) {
  return (
    <div className="af-field" data-required={required || undefined}
         style={{ gridColumn: `span ${span}`, ...style }}>
      <label className="af-field__label">{label}</label>
      {children}
      {hint && !error && <div className="af-field__hint">{hint}</div>}
      {error && <div className="af-field__error">{error}</div>}
    </div>
  );
}

// ── Summary header ──────────────────────────────────────────
function FlightSummary({ f, gliderBlock, towBlock }) {
  const aerotow = f.launchType === 'Aerotow';
  return (
    <div style={{
      padding: 'var(--s-4)',
      background: 'var(--color-bg-subtle)',
      border: '1px solid var(--color-border)',
      display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 'var(--s-4)',
    }} className="fe-summary">
      {[
        { l: 'Date',         v: f.date.slice(5),      m: f.airfield,    tabular: true },
        { l: 'Glider',       v: f.glider,             m: GLIDERS.find((a) => a.reg === f.glider)?.type || '—', tabular: true },
        { l: 'Glider block', v: gliderBlock || '—:—', m: `${f.gliderOff} → ${f.gliderOn}`, tabular: true },
        { l: 'Tow plane',    v: aerotow ? f.towAcft : '—', m: f.launchType, tabular: true },
        { l: 'Tow block',    v: aerotow ? (towBlock || '—:—') : '—', m: aerotow ? `${f.towOff} → ${f.towOn}` : '—', tabular: true },
        { l: 'Release',      v: `${f.releaseAlt} m`,  m: aerotow ? f.releaseTime : '—', tabular: true },
      ].map((s) => (
        <div key={s.l}>
          <div className="caps muted" style={{ marginBottom: 4 }}>{s.l}</div>
          <div className={s.tabular ? 'tabular' : ''} style={{ fontSize: 16, fontWeight: 500, lineHeight: 1.2 }}>{s.v}</div>
          <div className="muted tabular" style={{ fontSize: 12, marginTop: 2 }}>{s.m}</div>
        </div>
      ))}
    </div>
  );
}

// ── Step indicator ──────────────────────────────────────────
function Stepper({ steps, current, onJump }) {
  return (
    <ol className="fe-stepper">
      {steps.map((s, i) => {
        const state = i < current ? 'done' : i === current ? 'active' : 'todo';
        return (
          <li key={s.id} data-state={state} onClick={() => onJump(i)}>
            <span className="fe-stepper__num tabular">
              {state === 'done' ? <Icon name="check" size={12} /> : i + 1}
            </span>
            <div>
              <div className="caps muted">Step {i + 1}</div>
              <div className="fe-stepper__label">{s.label}</div>
              {s.hint && <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>{s.hint}</div>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

// ── Step 1: Launch ──────────────────────────────────────────
function StepLaunch({ f, set }) {
  return (
    <div className="fe-step">
      <div className="fe-step__hd">
        <h3>Launch</h3>
        <p className="muted">Where and how the glider got airborne.</p>
      </div>
      <div className="fe-step__bd" style={{ display: 'grid', gridTemplateColumns: 'repeat(12, 1fr)', gap: 'var(--form-gap)' }}>
        <Field label="Date" required span={3}>
          <input className="af-input tabular" type="date" value={f.date} onChange={(e) => set('date', e.target.value)} />
        </Field>
        <Field label="Airfield" required span={3}>
          <input className="af-input tabular" value={f.airfield}
                 onChange={(e) => set('airfield', e.target.value.toUpperCase())} maxLength={4} />
        </Field>
        <Field label="Launch method" required span={3}>
          <select className="af-select" value={f.launchType} onChange={(e) => set('launchType', e.target.value)}>
            <option>Aerotow</option>
            <option>Winch</option>
            <option>Self-launch</option>
          </select>
        </Field>
        <Field label="Release alt" required span={3}
               hint={`${f.releaseAlt} m AGL ≈ ${Math.round(f.releaseAlt * 3.281)} ft`}>
          <div className="af-input-grp">
            <input className="af-input tabular" type="number" step="50" value={f.releaseAlt}
                   onChange={(e) => set('releaseAlt', Number(e.target.value))} />
            <span className="af-input-grp__addon">m AGL</span>
          </div>
        </Field>
      </div>
    </div>
  );
}

// ── Step 2: Glider ──────────────────────────────────────────
function StepGlider({ f, set, gliderBlock }) {
  return (
    <div className="fe-step">
      <div className="fe-step__hd">
        <h3>Glider flight</h3>
        <p className="muted">The flown aircraft. Take-off time is shared with the tow.</p>
      </div>
      <div className="fe-step__bd" style={{ display: 'grid', gridTemplateColumns: 'repeat(12, 1fr)', gap: 'var(--form-gap)' }}>
        <Field label="Glider" required span={6}>
          <select className="af-select" value={f.glider} onChange={(e) => set('glider', e.target.value)}>
            {GLIDERS.map((a) => (
              <option key={a.reg} value={a.reg}>{a.reg} · {a.type}{a.seats === 2 ? ' (2-seat)' : ''}</option>
            ))}
          </select>
        </Field>
        <Field label="Flight type" required span={6}>
          <select className="af-select" value={f.gliderType} onChange={(e) => set('gliderType', e.target.value)}>
            {['Training', 'Solo', 'Local', 'XC', 'Competition', 'Check ride'].map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>

        <Field label="Pilot in command" required span={6}>
          <select className="af-select" value={f.gliderPic} onChange={(e) => set('gliderPic', e.target.value)}>
            {PILOTS.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
        </Field>
        <Field label="Passenger / 2nd seat" span={6}>
          <select className="af-select" value={f.gliderPax} onChange={(e) => set('gliderPax', e.target.value)}>
            <option value="">—</option>
            {PILOTS.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
        </Field>

        <Field label="Take-off" required span={4}>
          <input className="af-input tabular" type="time" value={f.gliderOff}
                 onChange={(e) => set('gliderOff', e.target.value)} />
        </Field>
        <Field label="Landing" required span={4}>
          <input className="af-input tabular" type="time" value={f.gliderOn}
                 onChange={(e) => set('gliderOn', e.target.value)} />
        </Field>
        <Field label="Block time" span={2} hint="auto">
          <input className="af-input tabular" value={gliderBlock || '—:—'} disabled />
        </Field>
        <Field label="Landings" required span={2}>
          <input className="af-input tabular" type="number" min="1" value={f.gliderLdgs}
                 onChange={(e) => set('gliderLdgs', Number(e.target.value))} />
        </Field>

        <Field label="Remarks" span={12}>
          <textarea className="af-textarea" rows={3} value={f.gliderRemarks}
                    onChange={(e) => set('gliderRemarks', e.target.value)} />
        </Field>
      </div>
    </div>
  );
}

// ── Step 3: Tow plane ───────────────────────────────────────
function StepTow({ f, set, towBlock }) {
  if (f.launchType !== 'Aerotow') {
    return (
      <div className="fe-step">
        <div className="fe-step__hd">
          <h3>Tow plane</h3>
          <p className="muted">Skipped — launch method is <strong>{f.launchType}</strong>.</p>
        </div>
        <div className="af-empty" style={{ padding: 'var(--s-8) var(--s-4)' }}>
          <h3>No paired tow flight</h3>
          <p>{f.launchType === 'Winch'
            ? 'Winch launches are logged on the glider entry only.'
            : 'Self-launching gliders fly their own departure.'}</p>
        </div>
      </div>
    );
  }
  const burn = Math.max(0, f.fuelStart - f.fuelEnd);
  return (
    <div className="fe-step">
      <div className="fe-step__hd">
        <h3>Tow plane flight</h3>
        <p className="muted">Tow take-off matches the glider; record the release and landing.</p>
      </div>
      <div className="fe-step__bd" style={{ display: 'grid', gridTemplateColumns: 'repeat(12, 1fr)', gap: 'var(--form-gap)' }}>
        <Field label="Tow plane" required span={6}>
          <select className="af-select" value={f.towAcft} onChange={(e) => set('towAcft', e.target.value)}>
            {TOW_PLANES.map((a) => (
              <option key={a.reg} value={a.reg}>{a.reg} · {a.type}</option>
            ))}
          </select>
        </Field>
        <Field label="Tow pilot" required span={6}>
          <select className="af-select" value={f.towPic} onChange={(e) => set('towPic', e.target.value)}>
            {PILOTS.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
        </Field>

        <Field label="Take-off" required span={3} hint="Linked to glider">
          <input className="af-input tabular" type="time" value={f.towOff} disabled />
        </Field>
        <Field label="Release time" required span={3}>
          <input className="af-input tabular" type="time" value={f.releaseTime}
                 onChange={(e) => set('releaseTime', e.target.value)} />
        </Field>
        <Field label="Landing" required span={3}>
          <input className="af-input tabular" type="time" value={f.towOn}
                 onChange={(e) => set('towOn', e.target.value)} />
        </Field>
        <Field label="Tow block" span={3} hint="auto">
          <input className="af-input tabular" value={towBlock || '—:—'} disabled />
        </Field>

        <div style={{ gridColumn: 'span 12' }}><div className="af-divider" style={{ margin: 0 }} /></div>

        <Field label="Fuel start" span={4}>
          <div className="af-input-grp">
            <input className="af-input tabular" type="number" value={f.fuelStart}
                   onChange={(e) => set('fuelStart', Number(e.target.value))} />
            <span className="af-input-grp__addon">L</span>
          </div>
        </Field>
        <Field label="Fuel end" span={4}>
          <div className="af-input-grp">
            <input className="af-input tabular" type="number" value={f.fuelEnd}
                   onChange={(e) => set('fuelEnd', Number(e.target.value))} />
            <span className="af-input-grp__addon">L</span>
          </div>
        </Field>
        <Field label="Burn" span={4} hint="auto-calculated">
          <div className="af-input-grp">
            <input className="af-input tabular" disabled value={burn} />
            <span className="af-input-grp__addon">L</span>
          </div>
        </Field>
      </div>
    </div>
  );
}

// ── Main ────────────────────────────────────────────────────
function FlightEntry({ onCancel, onSubmit }) {
  const flight = useFlight();
  const [step, setStep] = React.useState(0);
  const [saved, setSaved] = React.useState(false);

  const aerotow = flight.state.launchType === 'Aerotow';
  const steps = [
    { id: 'launch', label: 'Launch',     hint: 'date · airfield · method' },
    { id: 'glider', label: 'Glider',     hint: 'aircraft · crew · times' },
    { id: 'tow',    label: 'Tow plane',  hint: aerotow ? 'release · landing · fuel' : `skipped (${flight.state.launchType.toLowerCase()})` },
  ];

  const submit = () => {
    setSaved(true);
    setTimeout(() => { setSaved(false); onSubmit?.(flight.state); }, 900);
  };

  return (
    <>
      <div className="af-breadcrumb">
        <button onClick={onCancel}>Logbook</button>
        <Icon name="chevRight" size={12} />
        <span style={{ color: 'var(--color-fg)' }}>New flight</span>
      </div>
      <div className="af-page__hd">
        <div>
          <h1 className="af-page__title">Log flight</h1>
          <p className="af-page__sub">All times UTC. One paired entry per aerotow: glider + tow plane.</p>
        </div>
        <div className="af-page__actions">
          <button className="af-btn" onClick={onCancel}>Cancel</button>
          <button className="af-btn">Save draft</button>
          <button className="af-btn" data-variant="primary" onClick={submit} disabled={saved}>
            {saved ? <><Icon name="check" size={16} /> Saved</> : (aerotow ? 'Submit both' : 'Submit flight')}
          </button>
        </div>
      </div>

      <div style={{ marginBottom: 'var(--s-5)' }}>
        <FlightSummary f={flight.state} gliderBlock={flight.gliderBlock} towBlock={flight.towBlock} />
        {saved && <div className="af-progress" style={{ marginTop: 0 }}><div className="af-progress__bar" style={{ width: '100%' }} /></div>}
      </div>

      <Stepper steps={steps} current={step} onJump={setStep} />

      <div className="af-card" style={{ marginTop: 'var(--s-4)' }}>
        <div className="af-card__bd" style={{ padding: 'var(--s-5) var(--s-5) var(--s-6)' }}>
          {step === 0 && <StepLaunch f={flight.state} set={flight.set} />}
          {step === 1 && <StepGlider f={flight.state} set={flight.set} gliderBlock={flight.gliderBlock} />}
          {step === 2 && <StepTow    f={flight.state} set={flight.set} towBlock={flight.towBlock} />}
        </div>
        <div className="af-card__ft" style={{ justifyContent: 'space-between' }}>
          <button className="af-btn" disabled={step === 0} onClick={() => setStep((s) => Math.max(0, s - 1))}>
            <Icon name="chevLeft" size={14} /> Back
          </button>
          <div style={{ display: 'flex', gap: 'var(--s-2)', alignItems: 'center' }}>
            <span className="muted tabular" style={{ fontSize: 12 }}>{step + 1} / {steps.length}</span>
            {step < steps.length - 1 ? (
              <button className="af-btn" data-variant="primary" onClick={() => setStep((s) => s + 1)}>
                Next <Icon name="chevRight" size={14} />
              </button>
            ) : (
              <button className="af-btn" data-variant="primary" onClick={submit} disabled={saved}>
                {saved ? <><Icon name="check" size={16} /> Saved</> : (aerotow ? 'Submit both' : 'Submit flight')}
              </button>
            )}
          </div>
        </div>
      </div>

      <style>{`
        @media (max-width: 767px) {
          .fe-summary { grid-template-columns: repeat(2, 1fr) !important; }
          .fe-stepper { grid-template-columns: 1fr !important; }
          .fe-stepper li { border-left: 0 !important; border-top: 1px solid var(--color-border); }
          .fe-stepper li:first-child { border-top: 0; }
          .fe-step__bd { grid-template-columns: repeat(4, 1fr) !important; }
          .fe-step__bd > .af-field { grid-column: span 4 !important; }
        }
      `}</style>
    </>
  );
}

Object.assign(window, { FlightEntry });
