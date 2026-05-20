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
    blastRadiusBanner: 'Reference data — changes apply to all clubs.',
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
    readonlyBanner: 'Read-only. Managed by the system administrator.',
    title: 'Locations',
  },
};

export default en;
