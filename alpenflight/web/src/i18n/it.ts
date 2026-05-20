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
    title: 'Aeroporti',
    new: 'Nuovo aeroporto',
    blastRadiusBanner: 'Dati di riferimento — le modifiche si applicano a tutti i club.',
    readonlyBanner: 'Sola lettura. Gestito dall’amministratore di sistema.',
    fields: {
      name: 'Nome',
      shortName: 'Nome breve',
      icao: 'Codice ICAO',
      country: 'Paese',
      type: 'Tipo',
      latitude: 'Latitudine',
      longitude: 'Longitudine',
      description: 'Descrizione',
      inboundRouteRequired: 'Rotta di arrivo richiesta',
      outboundRouteRequired: 'Rotta di partenza richiesta',
      fastEntryRecord: 'Inserimento rapido',
    },
    inOutboundPoints: {
      title: 'Punti di entrata / uscita',
      add: 'Aggiungi punto',
      empty: 'Nessun punto di entrata / uscita.',
    },
    errors: {
      icaoDuplicate: 'Codice ICAO già in uso.',
    },
  },
};

export default it;
