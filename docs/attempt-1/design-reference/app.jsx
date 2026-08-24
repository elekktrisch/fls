// AlpenFlight prototype root.
// Multi-screen flow: landing → login → app (logbook ↔ entry ↔ reservations …)
// Tweaks: form layout, nav pattern, density.

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "navPattern": "topbar",
  "tableDensity": "comfortable"
}/*EDITMODE-END*/;

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [route, setRoute] = React.useState('landing'); // landing | login | logbook | entry | reservations | aircraft | members

  const goLandIn = () => setRoute('login');
  const signIn   = () => setRoute('home');
  const signOut  = () => setRoute('landing');

  const onLogFlight  = () => setRoute('entry');
  const onCancel     = () => setRoute('logbook');
  const onSubmit     = () => setRoute('logbook');

  // when the user changes the nav pattern in Tweaks, persist the route
  const navMode = t.navPattern; // 'topbar' | 'drawer'

  let content = null;
  if (route === 'landing') {
    content = <Landing onSignIn={goLandIn} />;
  } else if (route === 'login') {
    content = <Login onSignIn={signIn} onBack={() => setRoute('landing')} />;
  } else {
    let inner = null;
    if (route === 'home')              inner = <Home
                                                  onLogFlight={onLogFlight}
                                                  onOpenLogbook={() => setRoute('logbook')}
                                                  onOpenReservations={() => setRoute('reservations')}
                                                  onOpenStats={() => setRoute('stats')}
                                                  onOpenFlight={() => setRoute('logbook')} />;
    else if (route === 'logbook')      inner = <Logbook   onLogFlight={onLogFlight} density={t.tableDensity} />;
    else if (route === 'entry')        inner = <FlightEntry onCancel={onCancel} onSubmit={onSubmit} />;
    else if (route === 'reservations') inner = <Reservations />;
    else if (route === 'aircraft')     inner = <Aircraft />;
    else if (route === 'members')      inner = <Members />;
    else if (route === 'stats')        inner = <Stats onBack={() => setRoute('home')} />;
    content = (
      <div className="af-app" data-nav={navMode}>
        <Shell route={route} onNavigate={setRoute} onSignOut={signOut}>
          {inner}
        </Shell>
      </div>
    );
  }

  return (
    <>
      {content}
      <TweaksPanel title="AlpenFlight tweaks">
        <TweakSection label="Navigation" />
        <TweakRadio
          label="Pattern"
          value={t.navPattern}
          options={[
            { value: 'topbar', label: 'Top bar' },
            { value: 'drawer', label: 'Drawer' },
          ]}
          onChange={(v) => setTweak('navPattern', v)}
        />
        <TweakSection label="Lists" />
        <TweakRadio
          label="Density"
          value={t.tableDensity}
          options={[
            { value: 'comfortable', label: 'Comfortable' },
            { value: 'compact',     label: 'Compact' },
          ]}
          onChange={(v) => setTweak('tableDensity', v)}
        />
        <TweakSection label="Jump to" />
        <TweakRadio
          label="Screen"
          value={route === 'landing' || route === 'login' ? 'landing' : 'app'}
          options={[
            { value: 'landing', label: 'Public' },
            { value: 'app',     label: 'App' },
          ]}
          onChange={(v) => setRoute(v === 'landing' ? 'landing' : 'logbook')}
        />
      </TweaksPanel>
    </>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
