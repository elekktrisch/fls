// Lucide-style line icons. 24×24 viewBox, 1.5px stroke, currentColor.
// Path data adapted from the Lucide icon set (ISC license). Keep this file
// the only place where SVG path strings live in author code.

const __ICON_BASE = {
  width: 18,
  height: 18,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.5,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
};

// Each value is the inner path markup as a function returning JSX children.
const ICONS = {
  // ── brand glyph: a stylized triangle (paper-plane silhouette, custom-cut)
  plane: () => (
    <>
      <path d="M21 4 3 11l7 3 3 7 8-17z" />
      <path d="M10 14 21 4" />
    </>
  ),
  // ── nav / actions
  plus:   () => (<><path d="M12 5v14M5 12h14" /></>),
  check:  () => (<><path d="M5 12l4 4 10-10" /></>),
  x:      () => (<><path d="M6 6l12 12M6 18 18 6" /></>),
  menu:   () => (<><path d="M4 6h16M4 12h16M4 18h16" /></>),
  search: () => (<><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></>),
  filter: () => (<><path d="M3 5h18l-7 9v6l-4-2v-4z" /></>),
  more:   () => (<><circle cx="5"  cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></>),
  chevDown:  () => (<><path d="m6 9 6 6 6-6" /></>),
  chevRight: () => (<><path d="m9 6 6 6-6 6" /></>),
  chevLeft:  () => (<><path d="m15 6-6 6 6 6" /></>),
  arrowRight:() => (<><path d="M5 12h14M13 5l7 7-7 7" /></>),
  // ── domain
  calendar: () => (
    <>
      <rect x="3" y="5" width="18" height="16" />
      <path d="M3 10h18M8 3v4M16 3v4" />
    </>
  ),
  clock: () => (<><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>),
  user:  () => (<><circle cx="12" cy="8" r="4" /><path d="M4 21c1-4 4-6 8-6s7 2 8 6" /></>),
  users: () => (<><circle cx="9" cy="8" r="4" /><path d="M2 21c1-4 3-6 7-6s6 2 7 6" /><path d="M16 4a4 4 0 0 1 0 8M16 15c3 0 5 2 6 6" /></>),
  settings: () => (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
    </>
  ),
  logIn:  () => (<><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" /><path d="M10 17l5-5-5-5M15 12H3" /></>),
  logOut: () => (<><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="M16 17l5-5-5-5M21 12H9" /></>),
  mapPin: () => (<><path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0z" /><circle cx="12" cy="10" r="3" /></>),
  wind:   () => (<><path d="M3 8h12a3 3 0 1 0-3-3M3 12h17a3 3 0 1 1-3 3M3 16h9a3 3 0 1 1-3 3" /></>),
  gauge:  () => (<><path d="M12 14l4-4" /><path d="M3.5 17a9 9 0 1 1 17 0" /></>),
  download: () => (<><path d="M12 3v12M7 10l5 5 5-5M5 21h14" /></>),
  edit:    () => (<><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" /></>),
  trash:   () => (<><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2" /><path d="M19 6 18 20a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /></>),
  info:    () => (<><circle cx="12" cy="12" r="9" /><path d="M12 8h.01M11 12h1v5h1" /></>),
  alert:   () => (<><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></>),
  external:() => (<><path d="M15 3h6v6M21 3l-9 9M10 5H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5" /></>),
};

function Icon({ name, size = 18, className = '', style = {} }) {
  const draw = ICONS[name];
  if (!draw) return null;
  return (
    <svg {...__ICON_BASE} width={size} height={size}
         className={`af-icon ${className}`.trim()} style={style}>
      {draw()}
    </svg>
  );
}

// AlpenFlight wordmark — Roboto Medium "AlpenFlight" + brand glyph.
// The glyph: a sharp upward chevron / wing — purpose-built, simple geometry.
function Wordmark({ variant = 'full', color }) {
  const glyphColor = color || 'var(--color-accent)';
  const glyph = (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
         stroke={glyphColor} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
         aria-hidden="true" className="af-wm-glyph" style={{ color: glyphColor }}>
      {/* Stylized wing: ascending angled stroke + horizon mark */}
      <path d="M3 18 L21 6" />
      <path d="M3 18 L13 12" />
      <path d="M21 6 L21 12" />
    </svg>
  );
  if (variant === 'glyph') return glyph;
  return (
    <div className="af-topbar__wm" aria-label="AlpenFlight">
      {glyph}
      <span className="af-wm-text">AlpenFlight</span>
    </div>
  );
}

Object.assign(window, { Icon, Wordmark });
