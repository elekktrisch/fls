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
  publicStub: {
    back: 'Zurück zur Startseite',
    body: 'Die öffentliche Buchung ist in Vorbereitung. Bei Fragen wende dich direkt an deinen Verein.',
    discoveryFlight: 'Schnupperflug',
    scenicFlight: 'Mitflug',
    title: 'Demnächst verfügbar',
  },
};

export type Translations = typeof de;
export default de;
