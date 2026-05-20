import type { Translations } from './de';

const fr: Translations = {
  landing: {
    actions: {
      signIn: 'Se connecter',
      tryDemo: 'Essayer la démo',
    },
    footer: {
      imprint: 'Mentions légales',
      privacy: 'Confidentialité',
    },
    language: 'Langue',
    tagline: 'Carnet de vol, réservations et membres — pour les clubs suisses.',
  },
  locations: {
    title: 'Emplacements',
    new: 'Nouvel emplacement',
    blastRadiusBanner: 'Données de référence — les modifications s’appliquent à tous les clubs.',
    readonlyBanner: 'Lecture seule. Géré par l’administrateur système.',
    fields: {
      name: 'Nom',
      shortName: 'Nom court',
      icao: 'Code OACI',
      country: 'Pays',
      type: 'Type',
      latitude: 'Latitude',
      longitude: 'Longitude',
      description: 'Description',
      inboundRouteRequired: 'Route d’arrivée requise',
      outboundRouteRequired: 'Route de départ requise',
      fastEntryRecord: 'Saisie rapide',
    },
    inOutboundPoints: {
      title: 'Points d’arrivée / de départ',
      add: 'Ajouter un point',
      empty: 'Aucun point d’arrivée / de départ.',
    },
    errors: {
      icaoDuplicate: 'Le code OACI est déjà utilisé.',
    },
  },
};

export default fr;
