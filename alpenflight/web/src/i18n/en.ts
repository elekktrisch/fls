import type { Translations } from './de';

const en: Translations = {
  aircraft: {
    blastRadiusBanner: 'Club master data — changes affect only your club.',
    deleteConfirm: 'Delete aircraft "{{immatriculation}}"? This cannot be undone.',
    edit: {
      title: 'Edit aircraft',
      titleNew: 'New aircraft',
    },
    errors: {
      immatriculationDuplicate: 'Immatriculation is already in use.',
    },
    fields: {
      comment: 'Comment',
      competitionSign: 'Competition sign',
      daecIndex: 'DAEC index',
      flarmId: 'FLARM ID',
      homebase: 'Homebase',
      immatriculation: 'Immatriculation',
      manufacturer: 'Manufacturer',
      model: 'Model',
      mtom: 'MTOM (kg)',
      noiseClass: 'Noise class',
      noiseLevel: 'Noise level (dB)',
      seats: 'Seats',
      serialNumber: 'Serial number',
      spotLink: 'SPOT tracker link',
      type: 'Type',
      yearOfManufacture: 'Year of manufacture',
    },
    list: {
      columns: {
        manufacturer: 'Manufacturer',
        model: 'Model',
        seats: '{{count}} seats',
      },
      readonlyBanner: 'Read-only. Your club’s aircraft are managed by your club administrator.',
      typeFilter: {
        label: 'Filter by type',
      },
    },
    new: 'New aircraft',
    sections: {
      masterdata: 'Master data',
      operational: 'Operational data',
      technical: 'Technical data',
    },
    title: 'Aircraft',
  },
  flight: {
    conflict: {
      cancel: 'Keep editing',
      empty: '(empty)',
      field: {
        aircraftId: 'Aircraft',
        comment: 'Comment',
        couponNumber: 'Coupon number',
        flightDate: 'Flight date',
        flightTypeId: 'Flight type',
        ldgDateTime: 'Landing time',
        ldgLocationId: 'Landing location',
        nrOfLdgs: 'Landings',
        startDateTime: 'Start time',
        startLocationId: 'Start location',
        startTypeId: 'Start type',
      },
      intro: 'This flight was changed elsewhere. Pick which value wins for each field.',
      keepMine: 'Keep mine',
      keepTheirs: 'Stored value',
      resubmit: 'Save again',
      title: 'Concurrent edit',
    },
    reload: {
      action: 'Reload',
      message: 'Flight changed elsewhere — reload to get the latest.',
    },
  },
  landing: {
    actions: {
      requestAccess: 'Request access',
      signIn: 'Sign in',
      tryDemo: 'Try the demo',
    },
    eyebrow: 'Club operations · Switzerland',
    footer: {
      documentation: 'Documentation',
      imprint: 'Imprint',
      privacy: 'Privacy',
      status: 'Status',
    },
    headline: 'Log flights. Reserve aircraft. Run the airfield.',
    language: 'Language',
    splashLabel: 'Club airfield',
    stats: {
      clubs: 'clubs',
      flights: 'flights logged',
      pilots: 'pilots',
    },
    tagline:
      'Built for flying clubs that already exist and already fly. One quiet tool for the logbook, the reservation calendar, and the member roster.',
  },
  home: {
    admin: {
      heading: 'Club overview',
      tiles: {
        pendingValidation: 'Flights to validate',
        todayFlights: 'Flights today',
      },
    },
    greeting: {
      afternoon: 'Good afternoon, {{name}}',
      evening: 'Good evening, {{name}}',
      morning: 'Good morning, {{name}}',
    },
    lastFlight: {
      aircraft: 'Aircraft',
      empty: {
        cta: 'Log your first flight',
        message: 'No flights yet — log your first.',
      },
      error: 'Could not load your last flight.',
      flightType: 'Flight type',
      role: 'Role',
      roles: {
        coPilot: 'Co-pilot',
        flightCostInvoiceRecipient: 'Cost recipient',
        instructor: 'Instructor',
        observer: 'Observer',
        passenger: 'Passenger',
        pic: 'PIC',
        winchOperator: 'Winch operator',
      },
      route: 'Route',
      title: 'Your last flight',
    },
    pilotView: {
      toggle: 'Pilot view',
    },
    quickActions: {
      logFlight: 'Log flight',
      openLogbook: 'Open logbook',
    },
    reservations: {
      placeholder: 'Reservations coming soon.',
      title: 'Next reservation',
    },
    sysadmin: {
      heading: 'System overview',
      tenantEnter: 'Enter a club',
      tiles: {
        totalClubs: 'Clubs',
        totalFlights: 'Flights',
        totalUsers: 'Users',
      },
    },
  },
  locations: {
    admin: {
      banner:
        'Cross-tenant view — pick a club to operate on its Locations. Standard per-club management is at /locations.',
      clubLabel: 'Club',
      clubPlaceholder: 'Pick a club to view its Locations',
      clubPlaceholderLoading: 'Loading clubs…',
      clubsError: 'Failed to load clubs.',
      delete: 'Delete',
      deleteConfirm: 'Delete "{{name}}" from this club? This cannot be undone.',
      deleteError: 'Failed to delete the Location.',
      edit: 'Edit',
      locationsError: 'Failed to load Locations for the selected club.',
      new: 'New location',
      title: 'Locations admin (cross-tenant)',
    },
    blastRadiusBanner: 'Per-club masterdata — changes apply only to your club.',
    errors: {
      icaoDuplicate: 'ICAO code is already in use.',
    },
    fields: {
      country: 'Country',
      description: 'Description',
      fastEntryRecord: 'Fast-entry record',
      icao: 'ICAO code',
      inboundRouteRequired: 'Inbound route required',
      latitude: 'Latitude',
      longitude: 'Longitude',
      name: 'Name',
      outboundRouteRequired: 'Outbound route required',
      shortName: 'Short name',
      type: 'Type',
    },
    inOutboundPoints: {
      add: 'Add point',
      empty: 'No in/outbound points.',
      title: 'In/outbound points',
    },
    new: 'New location',
    readonlyBanner: "Read-only. Your club's Locations are managed by your club administrator.",
    title: 'Locations',
  },
  migrateHandshake: {
    copied: 'Handshake copied.',
    copy: 'Copy handshake',
    download: 'Download handshake file',
    error: 'Could not generate a key. Please try again.',
    expires: 'Valid until {{expiresAt}}',
    headline: 'Hand the public key to the export tool',
    jarPanel: {
      cta: 'Show me the export tool',
      title: 'Export tool required',
    },
    loading: 'Generating key…',
    pemHint: 'For reference only — give the export tool the downloaded handshake file.',
    pemLabel: 'Public key (PEM)',
    regenerate: 'Regenerate key',
    regenerateConfirm: {
      cancel: 'Cancel',
      confirm: 'Regenerate key',
      message:
        'A new key invalidates the previous one. If you already started the export with the old key, abandon it and start over.',
      title: 'Replace the key?',
    },
    regenerated:
      'New key generated. Download the handshake file again and restart the export — the previous file is invalid.',
    retry: 'Retry',
    tagline:
      'Download the handshake file and give it to the export tool. Your data is encrypted before it reaches our servers.',
  },
  publicStub: {
    back: 'Back to the landing page',
    body: 'Public booking is on the way. In the meantime, contact your club directly.',
    discoveryFlight: 'Discovery flight',
    scenicFlight: 'Scenic flight',
    title: 'Coming soon',
  },
  signup: {
    actions: {
      continueWithGoogle: 'Continue with Google',
      signIn: 'Sign in',
      signUp: 'Sign up',
    },
    alreadyHaveAccount: 'Already have an account?',
    errors: {
      unreachable: 'Sign-up is unavailable right now. Please try again later.',
    },
    headline: 'Create your AlpenFlight account',
    postLanding: {
      body: 'Your account is ready. The import workflow lands in a future release.',
      headline: 'Account created',
    },
    tagline:
      "We'll send a verification email. Your club is created on your first successful import.",
  },
};

export default en;
