// Top-bar / drawer shell.

function Shell({ route, onNavigate, onSignOut, children }) {
  const [drawerOpen, setDrawerOpen] = React.useState(false);
  const nav = [
    { id: 'home', label: 'Home' },
    { id: 'logbook', label: 'Logbook' },
    { id: 'reservations', label: 'Reservations' },
    { id: 'aircraft', label: 'Aircraft' },
    { id: 'members', label: 'Members' },
  ];
  const go = (id) => { onNavigate(id); setDrawerOpen(false); };

  return (
    <>
      <header className="af-topbar">
        <button className="af-hamburger" aria-label="Open menu"
                onClick={() => setDrawerOpen(true)}>
          <Icon name="menu" size={18} />
        </button>
        <Wordmark />
        <nav className="af-nav" aria-label="Primary">
          {nav.map((n) => (
            <button key={n.id} type="button"
                    className="af-nav__item"
                    data-active={route === n.id}
                    onClick={() => go(n.id)}>
              {n.label}
            </button>
          ))}
        </nav>
        <div className="af-topbar__spacer" />
        <button className="af-iconbtn hide-on-mobile" aria-label="Search">
          <Icon name="search" size={18} />
        </button>
        <div className="af-topbar__user hide-on-mobile">
          <div className="af-avatar">MW</div>
          <span>M. Weber</span>
        </div>
        <button className="af-iconbtn" aria-label="Sign out" onClick={onSignOut}>
          <Icon name="logOut" size={18} />
        </button>
      </header>

      {drawerOpen && (
        <div className="af-drawer" onClick={() => setDrawerOpen(false)}>
          <div className="af-drawer__panel" onClick={(e) => e.stopPropagation()}>
            <div className="af-drawer__hd">
              <Wordmark />
              <div style={{ flex: 1 }} />
              <button className="af-iconbtn" aria-label="Close" onClick={() => setDrawerOpen(false)}>
                <Icon name="x" size={18} />
              </button>
            </div>
            <div className="af-drawer__nav">
              {nav.map((n) => (
                <button key={n.id} type="button"
                        className="af-drawer__item"
                        data-active={route === n.id}
                        onClick={() => go(n.id)}>
                  {n.label}
                </button>
              ))}
            </div>
            <div style={{ flex: 1 }} />
            <div className="af-drawer__nav" style={{ borderTop: '1px solid var(--color-border)' }}>
              <button className="af-drawer__item" onClick={onSignOut}>Sign out</button>
            </div>
          </div>
        </div>
      )}

      <main className="af-page">{children}</main>

      <footer style={{
        borderTop: '1px solid var(--color-border)',
        padding: 'var(--s-4) var(--s-6)',
        color: 'var(--color-fg-muted)',
        fontSize: 'var(--fs-xs)',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        gap: 'var(--s-4)', flexWrap: 'wrap',
      }}>
        <span>Alpenflug-Club Birrfeld · LSZF</span>
        <span className="tabular">v0.4.0 · 21 May 2026</span>
      </footer>
    </>
  );
}

Object.assign(window, { Shell });
