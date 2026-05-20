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
    blastRadiusBanner: 'Données de référence — les modifications s’appliquent à tous les clubs.',
    errors: {
      icaoDuplicate: 'Le code OACI est déjà utilisé.',
    },
    fields: {
      country: 'Pays',
      description: 'Description',
      fastEntryRecord: 'Saisie rapide',
      icao: 'Code OACI',
      inboundRouteRequired: 'Route d’arrivée requise',
      latitude: 'Latitude',
      longitude: 'Longitude',
      name: 'Nom',
      outboundRouteRequired: 'Route de départ requise',
      shortName: 'Nom court',
      type: 'Type',
    },
    inOutboundPoints: {
      add: 'Ajouter un point',
      empty: 'Aucun point d’arrivée / de départ.',
      title: 'Points d’arrivée / de départ',
    },
    new: 'Nouvel emplacement',
    readonlyBanner: 'Lecture seule. Géré par l’administrateur système.',
    title: 'Emplacements',
  },
};

export default fr;
