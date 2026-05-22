import type { Translations } from './de';

const fr: Translations = {
  landing: {
    actions: {
      requestAccess: 'Demander un accès',
      signIn: 'Se connecter',
      tryDemo: 'Essayer la démo',
    },
    eyebrow: 'Opérations de club · Suisse',
    footer: {
      documentation: 'Documentation',
      imprint: 'Mentions légales',
      privacy: 'Confidentialité',
      status: 'État',
    },
    headline: 'Enregistrer les vols. Réserver les aéronefs. Faire vivre l’aérodrome.',
    language: 'Langue',
    splashLabel: 'Aérodrome du club',
    stats: {
      clubs: 'clubs',
      flights: 'vols enregistrés',
      pilots: 'pilotes',
    },
    tagline:
      'Pensé pour les clubs aéronautiques qui existent déjà et volent déjà. Un outil discret pour le carnet de vol, le calendrier des réservations et le registre des membres.',
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
  publicStub: {
    back: 'Retour à la page d’accueil',
    body: 'La réservation publique est en préparation. En attendant, contactez directement votre club.',
    discoveryFlight: 'Vol de découverte',
    scenicFlight: 'Vol panoramique',
    title: 'Bientôt disponible',
  },
};

export default fr;
