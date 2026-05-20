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
};

export type Translations = typeof de;
export default de;
