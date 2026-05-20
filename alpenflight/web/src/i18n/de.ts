/**
 * Canonical locale. The shape exported here defines `Translations` —
 * every other locale file imports the type and is forced to mirror
 * the shape exactly. Add a key here first; the other locales become
 * compile errors until they add the same key.
 */
const de = {
  landing: {
    actions: {
      signIn: 'Anmelden',
      tryDemo: 'Demo ausprobieren',
    },
    footer: {
      imprint: 'Impressum',
      privacy: 'Datenschutz',
    },
    language: 'Sprache',
    tagline: 'Flugbuch, Reservationen und Mitglieder — für Schweizer Vereine.',
  },
  locations: {
    blastRadiusBanner: 'Referenzdaten — Änderungen wirken für alle Vereine.',
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
    readonlyBanner: 'Schreibgeschützt. Wird vom Systemadministrator verwaltet.',
    title: 'Flugplätze',
  },
};

export type Translations = typeof de;
export default de;
