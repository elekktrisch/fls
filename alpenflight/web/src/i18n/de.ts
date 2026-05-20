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
    title: 'Flugplätze',
    new: 'Neuer Flugplatz',
    blastRadiusBanner: 'Referenzdaten — Änderungen wirken für alle Vereine.',
    readonlyBanner: 'Schreibgeschützt. Wird vom Systemadministrator verwaltet.',
    fields: {
      name: 'Name',
      shortName: 'Kürzel',
      icao: 'ICAO-Code',
      country: 'Land',
      type: 'Typ',
      latitude: 'Breitengrad',
      longitude: 'Längengrad',
      description: 'Beschreibung',
      inboundRouteRequired: 'Anflug-Route erforderlich',
      outboundRouteRequired: 'Abflug-Route erforderlich',
      fastEntryRecord: 'Schnellerfassung',
    },
    inOutboundPoints: {
      title: 'An-/Abflugpunkte',
      add: 'Punkt hinzufügen',
      empty: 'Keine An-/Abflugpunkte.',
    },
    errors: {
      icaoDuplicate: 'ICAO-Code wird bereits verwendet.',
    },
  },
};

export type Translations = typeof de;
export default de;
