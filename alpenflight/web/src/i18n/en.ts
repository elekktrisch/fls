import type { Translations } from './de';

const en: Translations = {
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
  publicStub: {
    back: 'Back to the landing page',
    body: 'Public booking is on the way. In the meantime, contact your club directly.',
    discoveryFlight: 'Discovery flight',
    scenicFlight: 'Scenic flight',
    title: 'Coming soon',
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
};

export default en;
