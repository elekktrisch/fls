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
    title: 'Locations',
    new: 'New location',
    blastRadiusBanner: 'Reference data — changes apply to all clubs.',
    readonlyBanner: 'Read-only. Managed by the system administrator.',
    fields: {
      name: 'Name',
      shortName: 'Short name',
      icao: 'ICAO code',
      country: 'Country',
      type: 'Type',
      latitude: 'Latitude',
      longitude: 'Longitude',
      description: 'Description',
      inboundRouteRequired: 'Inbound route required',
      outboundRouteRequired: 'Outbound route required',
      fastEntryRecord: 'Fast-entry record',
    },
    inOutboundPoints: {
      title: 'In/outbound points',
      add: 'Add point',
      empty: 'No in/outbound points.',
    },
    errors: {
      icaoDuplicate: 'ICAO code is already in use.',
    },
  },
};

export default en;
