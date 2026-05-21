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
    admin: {
      banner:
        'Vue inter-clubs — choisissez un club pour gérer ses emplacements. La gestion standard par club se trouve sous /locations.',
      clubLabel: 'Club',
      clubPlaceholder: 'Choisir un club',
      clubPlaceholderLoading: 'Chargement des clubs…',
      clubsError: 'Échec du chargement des clubs.',
      delete: 'Supprimer',
      deleteConfirm: 'Supprimer « {{name}} » de ce club ? Cette action est irréversible.',
      deleteError: 'Échec de la suppression de l’emplacement.',
      edit: 'Modifier',
      locationsError: 'Échec du chargement des emplacements du club choisi.',
      new: 'Nouvel emplacement',
      title: 'Emplacements – inter-clubs',
    },
    blastRadiusBanner:
      'Données propres au club — les modifications ne s’appliquent qu’à votre club.',
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
    readonlyBanner:
      'Lecture seule. Les emplacements de votre club sont gérés par votre administrateur de club.',
    title: 'Emplacements',
  },
};

export default fr;
