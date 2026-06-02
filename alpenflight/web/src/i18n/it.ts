import type { Translations } from './de';

const it: Translations = {
  aircraft: {
    blastRadiusBanner: 'Dati anagrafici del club — le modifiche valgono solo per il tuo club.',
    deleteConfirm: 'Eliminare l’aeromobile «{{immatriculation}}»? L’operazione è irreversibile.',
    edit: {
      title: 'Modifica aeromobile',
      titleNew: 'Nuovo aeromobile',
    },
    errors: {
      immatriculationDuplicate: 'L’immatricolazione è già in uso.',
    },
    fields: {
      comment: 'Commento',
      competitionSign: 'Sigla di gara',
      daecIndex: 'Indice DAEC',
      flarmId: 'ID FLARM',
      homebase: 'Base di partenza',
      immatriculation: 'Immatricolazione',
      manufacturer: 'Costruttore',
      model: 'Modello',
      mtom: 'MTOM (kg)',
      noiseClass: 'Classe di rumore',
      noiseLevel: 'Livello di rumore (dB)',
      seats: 'Posti',
      serialNumber: 'Numero di serie',
      spotLink: 'Link tracker SPOT',
      type: 'Tipo',
      yearOfManufacture: 'Anno di costruzione',
    },
    list: {
      readonlyBanner:
        'Sola lettura. Gli aeromobili del tuo club sono gestiti dall’amministratore del club.',
      typeFilter: {
        label: 'Filtra per tipo',
      },
    },
    new: 'Nuovo aeromobile',
    sections: {
      masterdata: 'Dati anagrafici',
      operational: 'Dati operativi',
      technical: 'Dati tecnici',
    },
    title: 'Aeromobili',
  },
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
  home: {
    greeting: {
      afternoon: 'Buon pomeriggio, {{name}}',
      evening: 'Buona sera, {{name}}',
      morning: 'Buongiorno, {{name}}',
    },
    lastFlight: {
      aircraft: 'Aeromobile',
      empty: {
        cta: 'Registra il primo',
        message: 'Nessun volo ancora — registra il primo.',
      },
      error: 'Impossibile caricare il tuo ultimo volo.',
      flightType: 'Tipo di volo',
      role: 'Ruolo',
      roles: {
        coPilot: 'Copilota',
        flightCostInvoiceRecipient: 'Destinatario fattura',
        instructor: 'Istruttore',
        observer: 'Osservatore',
        passenger: 'Passeggero',
        pic: 'PIC',
        winchOperator: 'Operatore verricello',
      },
      route: 'Rotta',
      title: 'Il tuo ultimo volo',
    },
    quickActions: {
      logFlight: 'Registra volo',
      openLogbook: 'Apri diario',
    },
    reservations: {
      placeholder: 'Prenotazioni in arrivo.',
      title: 'Prossima prenotazione',
    },
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
  migrateHandshake: {
    copied: 'Handshake copiato.',
    copy: 'Copia handshake',
    download: 'Scarica il file di handshake',
    error: 'Impossibile generare la chiave. Riprova.',
    expires: 'Valido fino al {{expiresAt}}',
    headline: 'Consegna la chiave pubblica allo strumento di esportazione',
    jarPanel: {
      cta: 'Apri lo strumento di esportazione',
      title: 'Strumento di esportazione richiesto',
    },
    loading: 'Generazione della chiave…',
    pemHint:
      'Solo a scopo informativo — fornisci allo strumento di esportazione il file di handshake scaricato.',
    pemLabel: 'Chiave pubblica (PEM)',
    regenerate: 'Genera una nuova chiave',
    regenerateConfirm: {
      cancel: 'Annulla',
      confirm: 'Genera una nuova chiave',
      message:
        'Una nuova chiave invalida la precedente. Se hai già iniziato l’esportazione con la vecchia chiave, abbandonala e ricomincia.',
      title: 'Sostituire la chiave?',
    },
    regenerated:
      'Nuova chiave generata. Scarica di nuovo il file di handshake e ricomincia l’esportazione — il file precedente non è più valido.',
    retry: 'Riprova',
    tagline:
      'Scarica il file di handshake e forniscilo allo strumento di esportazione. I tuoi dati vengono crittografati prima di raggiungere i nostri server.',
  },
  publicStub: {
    back: 'Torna alla pagina iniziale',
    body: 'La prenotazione pubblica è in arrivo. Nel frattempo, contatta direttamente il tuo club.',
    discoveryFlight: 'Volo di scoperta',
    scenicFlight: 'Volo panoramico',
    title: 'Disponibile a breve',
  },
  signup: {
    actions: {
      continueWithGoogle: 'Continua con Google',
      signIn: 'Accedi',
      signUp: 'Crea account',
    },
    alreadyHaveAccount: 'Hai già un account?',
    errors: {
      unreachable: 'Iscrizione non disponibile al momento. Riprova più tardi.',
    },
    headline: 'Crea il tuo account AlpenFlight',
    postLanding: {
      body: 'Il tuo account è pronto. La procedura di import arriverà in una prossima versione.',
      headline: 'Account creato',
    },
    tagline: 'Riceverai una email di verifica. Il club nasce al primo import riuscito.',
  },
};

export default it;
