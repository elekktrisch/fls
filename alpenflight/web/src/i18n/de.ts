/**
 * Canonical locale. The shape exported here defines `Translations` —
 * every other locale file imports the type and is forced to mirror
 * the shape exactly. Add a key here first; the other locales become
 * compile errors until they add the same key.
 */
const de = {
  landing: {
    actions: {
      requestAccess: 'Zugang anfragen',
      signIn: 'Anmelden',
      tryDemo: 'Demo ausprobieren',
    },
    eyebrow: 'Vereinsbetrieb · Schweiz',
    footer: {
      documentation: 'Dokumentation',
      imprint: 'Impressum',
      privacy: 'Datenschutz',
      status: 'Status',
    },
    headline: 'Flüge protokollieren. Flugzeuge reservieren. Den Flugplatz führen.',
    language: 'Sprache',
    splashLabel: 'Vereinsflugplatz',
    stats: {
      clubs: 'Vereine',
      flights: 'protokollierte Flüge',
      pilots: 'Piloten',
    },
    tagline:
      'Für Flugvereine, die schon bestehen und schon fliegen. Ein leises Werkzeug für Flugbuch, Reservationskalender und Mitgliederverwaltung.',
  },
  home: {
    greeting: {
      afternoon: 'Guten Tag, {{name}}',
      evening: 'Guten Abend, {{name}}',
      morning: 'Guten Morgen, {{name}}',
    },
    lastFlight: {
      aircraft: 'Flugzeug',
      empty: {
        cta: 'Ersten Flug erfassen',
        message: 'Noch keine Flüge — erfasse deinen ersten.',
      },
      error: 'Letzter Flug konnte nicht geladen werden.',
      flightType: 'Flugart',
      role: 'Rolle',
      roles: {
        coPilot: 'Copilot',
        flightCostInvoiceRecipient: 'Rechnungsempfänger',
        instructor: 'Fluglehrer',
        observer: 'Beobachter',
        passenger: 'Passagier',
        pic: 'PIC',
        winchOperator: 'Windenführer',
      },
      route: 'Route',
      title: 'Dein letzter Flug',
    },
    quickActions: {
      logFlight: 'Flug erfassen',
      openLogbook: 'Flugbuch öffnen',
    },
    reservations: {
      placeholder: 'Reservationen folgen demnächst.',
      title: 'Nächste Reservation',
    },
  },
  locations: {
    admin: {
      banner:
        'Vereinsübergreifende Sicht — wähle einen Verein, um dessen Flugplätze zu verwalten. Die normale Vereinsverwaltung erfolgt unter /locations.',
      clubLabel: 'Verein',
      clubPlaceholder: 'Verein auswählen',
      clubPlaceholderLoading: 'Vereine werden geladen…',
      clubsError: 'Vereine konnten nicht geladen werden.',
      delete: 'Löschen',
      deleteConfirm:
        '«{{name}}» aus diesem Verein löschen? Dies kann nicht rückgängig gemacht werden.',
      deleteError: 'Flugplatz konnte nicht gelöscht werden.',
      edit: 'Bearbeiten',
      locationsError: 'Flugplätze des gewählten Vereins konnten nicht geladen werden.',
      new: 'Neuer Flugplatz',
      title: 'Flugplätze – vereinsübergreifend',
    },
    blastRadiusBanner: 'Vereinsstammdaten — Änderungen wirken nur für deinen Verein.',
    errors: {
      icaoDuplicate: 'ICAO-Code wird bereits verwendet.',
    },
    fields: {
      country: 'Land',
      description: 'Beschreibung',
      fastEntryRecord: 'Schnellerfassung',
      icao: 'ICAO-Code',
      inboundRouteRequired: 'Anflug-Route erforderlich',
      latitude: 'Breitengrad',
      longitude: 'Längengrad',
      name: 'Name',
      outboundRouteRequired: 'Abflug-Route erforderlich',
      shortName: 'Kürzel',
      type: 'Typ',
    },
    inOutboundPoints: {
      add: 'Punkt hinzufügen',
      empty: 'Keine An-/Abflugpunkte.',
      title: 'An-/Abflugpunkte',
    },
    new: 'Neuer Flugplatz',
    readonlyBanner:
      'Schreibgeschützt. Die Flugplätze deines Vereins werden von deinem Vereinsadministrator verwaltet.',
    title: 'Flugplätze',
  },
  migrateHandshake: {
    copied: 'In die Zwischenablage kopiert.',
    copy: 'In Zwischenablage kopieren',
    download: 'Handshake-Datei herunterladen',
    error: 'Schlüssel konnte nicht erzeugt werden. Bitte erneut versuchen.',
    expires: 'Gültig bis {{expiresAt}}',
    headline: 'Öffentlichen Schlüssel an die Export-App übergeben',
    jarPanel: {
      cta: 'Export-Anleitung öffnen',
      title: 'Export-App benötigt',
    },
    loading: 'Schlüssel wird erzeugt …',
    pemLabel: 'Öffentlicher Schlüssel (PEM)',
    regenerate: 'Neuen Schlüssel erzeugen',
    regenerateConfirm: {
      cancel: 'Abbrechen',
      confirm: 'Neuen Schlüssel erzeugen',
      message:
        'Ein neuer Schlüssel macht den vorherigen ungültig. Wenn der alte Schlüssel bereits in der Export-App eingefügt wurde, muss der Export von vorne gestartet werden.',
      title: 'Schlüssel ersetzen?',
    },
    regenerated: 'Neuer Schlüssel erzeugt. Lade die Handshake-Datei erneut herunter und starte den Export damit neu — die vorherige Datei ist ungültig.',
    retry: 'Erneut versuchen',
    tagline:
      'Lade die Handshake-Datei herunter und übergib sie der Export-App. Die Daten werden damit verschlüsselt, bevor sie unsere Server erreichen.',
  },
  publicStub: {
    back: 'Zurück zur Startseite',
    body: 'Die öffentliche Buchung ist in Vorbereitung. Bei Fragen wende dich direkt an deinen Verein.',
    discoveryFlight: 'Schnupperflug',
    scenicFlight: 'Mitflug',
    title: 'Demnächst verfügbar',
  },
  signup: {
    actions: {
      continueWithGoogle: 'Mit Google fortfahren',
      signIn: 'Anmelden',
      signUp: 'Registrieren',
    },
    alreadyHaveAccount: 'Bereits ein Konto?',
    errors: {
      unreachable: 'Anmeldung nicht erreichbar. Bitte später erneut versuchen.',
    },
    headline: 'AlpenFlight-Konto erstellen',
    postLanding: {
      body: 'Dein Konto ist bereit. Der Import-Workflow folgt in einer späteren Version.',
      headline: 'Konto erstellt',
    },
    tagline:
      'E-Mail-Verifizierung folgt per Mail. Dein Verein wird nach dem ersten erfolgreichen Import angelegt.',
  },
};

export type Translations = typeof de;
export default de;
