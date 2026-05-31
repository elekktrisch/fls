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
  home: {
    greeting: {
      afternoon: 'Bon après-midi, {{name}}',
      evening: 'Bonsoir, {{name}}',
      morning: 'Bonjour, {{name}}',
    },
    lastFlight: {
      aircraft: 'Avion',
      empty: {
        cta: 'Enregistrer le premier',
        message: 'Aucun vol pour l’instant — enregistre ton premier.',
      },
      error: 'Impossible de charger ton dernier vol.',
      flightType: 'Type de vol',
      role: 'Rôle',
      roles: {
        coPilot: 'Copilote',
        flightCostInvoiceRecipient: 'Destinataire facturé',
        instructor: 'Instructeur',
        observer: 'Observateur',
        passenger: 'Passager',
        pic: 'PIC',
        winchOperator: 'Treuilliste',
      },
      route: 'Route',
      title: 'Ton dernier vol',
    },
    quickActions: {
      logFlight: 'Enregistrer un vol',
      openLogbook: 'Ouvrir le carnet',
    },
    reservations: {
      placeholder: 'Réservations bientôt disponibles.',
      title: 'Prochaine réservation',
    },
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
  migrateHandshake: {
    copied: 'Copié dans le presse-papiers.',
    copy: 'Copier dans le presse-papiers',
    download: 'Télécharger le fichier de handshake',
    error: 'Impossible de générer la clé. Veuillez réessayer.',
    expires: 'Valide jusqu’au {{expiresAt}}',
    headline: 'Transmettre la clé publique à l’outil d’export',
    jarPanel: {
      cta: 'Afficher l’outil d’export',
      title: 'Outil d’export requis',
    },
    loading: 'Génération de la clé…',
    pemHint: 'À titre indicatif — fournissez à l’outil d’export le fichier de handshake téléchargé.',
    pemLabel: 'Clé publique (PEM)',
    regenerate: 'Générer une nouvelle clé',
    regenerateConfirm: {
      cancel: 'Annuler',
      confirm: 'Générer une nouvelle clé',
      message:
        'Une nouvelle clé rend la précédente invalide. Si vous avez déjà commencé l’export avec l’ancienne clé, abandonnez-le et recommencez.',
      title: 'Remplacer la clé ?',
    },
    regenerated:
      'Nouvelle clé générée. Retéléchargez le fichier de handshake et recommencez l’export — le fichier précédent est invalide.',
    retry: 'Réessayer',
    tagline:
      'Téléchargez le fichier de handshake et fournissez-le à l’outil d’export. Vos données sont chiffrées avant d’atteindre nos serveurs.',
  },
  publicStub: {
    back: 'Retour à la page d’accueil',
    body: 'La réservation publique est en préparation. En attendant, contactez directement votre club.',
    discoveryFlight: 'Vol de découverte',
    scenicFlight: 'Vol panoramique',
    title: 'Bientôt disponible',
  },
  signup: {
    actions: {
      continueWithGoogle: 'Continuer avec Google',
      signIn: 'Se connecter',
      signUp: 'Créer un compte',
    },
    alreadyHaveAccount: 'Déjà un compte ?',
    errors: {
      unreachable: 'Inscription indisponible pour le moment. Veuillez réessayer plus tard.',
    },
    headline: 'Créer votre compte AlpenFlight',
    postLanding: {
      body: 'Votre compte est prêt. Le flux d’import arrive dans une prochaine version.',
      headline: 'Compte créé',
    },
    tagline:
      'Un e-mail de vérification suivra. Votre club est créé après votre premier import réussi.',
  },
};

export default fr;
