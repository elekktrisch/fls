import type { Translations } from './de';

const en: Translations = {
  landing: {
    actions: {
      signIn: 'Sign in',
      tryDemo: 'Try the demo',
    },
    footer: {
      imprint: 'Imprint',
      privacy: 'Privacy',
    },
    language: 'Language',
    tagline: 'Flight logging, reservations, members — for Swiss clubs.',
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
