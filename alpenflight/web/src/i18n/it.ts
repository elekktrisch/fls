import type { Translations } from './de';

const it: Translations = {
  landing: {
    actions: {
      signIn: 'Accedi',
      tryDemo: 'Prova la demo',
    },
    footer: {
      imprint: 'Note legali',
      privacy: 'Privacy',
    },
    language: 'Lingua',
    tagline: 'Diario di volo, prenotazioni e soci — per i club svizzeri.',
  },
  locations: {
    blastRadiusBanner: 'Dati di riferimento — le modifiche si applicano a tutti i club.',
    errors: {
      icaoDuplicate: 'Codice ICAO già in uso.',
    },
    fields: {
      country: 'Paese',
      description: 'Descrizione',
      fastEntryRecord: 'Inserimento rapido',
      icao: 'Codice ICAO',
      inboundRouteRequired: 'Rotta di arrivo richiesta',
      latitude: 'Latitudine',
      longitude: 'Longitudine',
      name: 'Nome',
      outboundRouteRequired: 'Rotta di partenza richiesta',
      shortName: 'Nome breve',
      type: 'Tipo',
    },
    inOutboundPoints: {
      add: 'Aggiungi punto',
      empty: 'Nessun punto di entrata / uscita.',
      title: 'Punti di entrata / uscita',
    },
    new: 'Nuovo aeroporto',
    readonlyBanner: 'Sola lettura. Gestito dall’amministratore di sistema.',
    title: 'Aeroporti',
  },
};

export default it;
