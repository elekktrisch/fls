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
    blastRadiusBanner: 'Dati specifici del club — le modifiche si applicano solo al tuo club.',
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
    readonlyBanner: 'Sola lettura. Gli aeroporti del tuo club sono gestiti dall’amministratore del club.',
    title: 'Aeroporti',
  },
};

export default it;
