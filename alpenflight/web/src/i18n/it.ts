import type { Translations } from './de';

const it: Translations = {
  landing: {
    actions: {
      requestAccess: 'Richiedi l’accesso',
      signIn: 'Accedi',
      tryDemo: 'Prova la demo',
    },
    eyebrow: 'Operazioni del club · Svizzera',
    footer: {
      documentation: 'Documentazione',
      imprint: 'Note legali',
      privacy: 'Privacy',
      status: 'Stato',
    },
    headline: 'Registrare i voli. Prenotare gli aeromobili. Gestire l’aerodromo.',
    language: 'Lingua',
    splashLabel: 'Aerodromo del club',
    stats: {
      clubs: 'club',
      flights: 'voli registrati',
      pilots: 'piloti',
    },
    tagline:
      'Pensato per i club di volo che già esistono e già volano. Uno strumento discreto per diario di volo, calendario delle prenotazioni e registro dei soci.',
  },
  locations: {
    admin: {
      banner:
        'Vista trasversale — scegli un club per gestirne gli aeroporti. La gestione per club è in /locations.',
      clubLabel: 'Club',
      clubPlaceholder: 'Seleziona un club',
      clubPlaceholderLoading: 'Caricamento club…',
      clubsError: 'Caricamento dei club non riuscito.',
      delete: 'Elimina',
      deleteConfirm: 'Eliminare «{{name}}» da questo club? L’operazione non è reversibile.',
      deleteError: 'Eliminazione dell’aeroporto non riuscita.',
      edit: 'Modifica',
      locationsError: 'Caricamento degli aeroporti del club selezionato non riuscito.',
      new: 'Nuovo aeroporto',
      title: 'Aeroporti – trasversale',
    },
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
    readonlyBanner:
      'Sola lettura. Gli aeroporti del tuo club sono gestiti dall’amministratore del club.',
    title: 'Aeroporti',
  },
  publicStub: {
    back: 'Torna alla pagina iniziale',
    body: 'La prenotazione pubblica è in arrivo. Nel frattempo, contatta direttamente il tuo club.',
    discoveryFlight: 'Volo di scoperta',
    scenicFlight: 'Volo panoramico',
    title: 'Disponibile a breve',
  },
};

export default it;
