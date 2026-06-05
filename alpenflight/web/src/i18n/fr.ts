import type { Translations } from './de';

const fr: Translations = {
  aircraft: {
    blastRadiusBanner:
      'Données de référence du club — les modifications n’affectent que votre club.',
    deleteConfirm: 'Supprimer l’aéronef « {{immatriculation}} » ? Cette action est irréversible.',
    edit: {
      title: 'Modifier l’aéronef',
      titleNew: 'Nouvel aéronef',
    },
    errors: {
      immatriculationDuplicate: 'L’immatriculation est déjà utilisée.',
    },
    fields: {
      comment: 'Commentaire',
      competitionSign: 'Indicatif de compétition',
      daecIndex: 'Indice DAEC',
      flarmId: 'Identifiant FLARM',
      homebase: 'Base d’attache',
      immatriculation: 'Immatriculation',
      manufacturer: 'Constructeur',
      model: 'Modèle',
      mtom: 'MTOM (kg)',
      noiseClass: 'Classe de bruit',
      noiseLevel: 'Niveau de bruit (dB)',
      seats: 'Sièges',
      serialNumber: 'Numéro de série',
      spotLink: 'Lien du traceur SPOT',
      type: 'Type',
      yearOfManufacture: 'Année de construction',
    },
    list: {
      columns: {
        manufacturer: 'Constructeur',
        model: 'Modèle',
        seats: '{{count}} places',
      },
      readonlyBanner:
        'Lecture seule. Les aéronefs de votre club sont gérés par votre administrateur de club.',
      typeFilter: {
        label: 'Filtrer par type',
      },
    },
    new: 'Nouvel aéronef',
    sections: {
      masterdata: 'Données de base',
      operational: 'Données opérationnelles',
      technical: 'Données techniques',
    },
    title: 'Aéronefs',
  },
  flight: {
    conflict: {
      cancel: 'Continuer la modification',
      empty: '(vide)',
      field: {
        aircraftId: 'Aéronef',
        comment: 'Commentaire',
        couponNumber: 'Numéro de coupon',
        flightDate: 'Date du vol',
        flightTypeId: 'Type de vol',
        ldgDateTime: "Heure d'atterrissage",
        ldgLocationId: "Lieu d'atterrissage",
        nrOfLdgs: 'Atterrissages',
        startDateTime: 'Heure de départ',
        startLocationId: 'Lieu de départ',
        startTypeId: 'Type de départ',
      },
      intro: 'Ce vol a été modifié ailleurs. Choisissez la valeur retenue pour chaque champ.',
      keepMine: 'Ma saisie',
      keepTheirs: 'Valeur enregistrée',
      resubmit: 'Enregistrer à nouveau',
      title: 'Modification concurrente',
    },
    reload: {
      action: 'Recharger',
      message: 'Vol modifié ailleurs — rechargez pour obtenir la dernière version.',
    },
  },
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
    admin: {
      heading: 'Aperçu du club',
      tiles: {
        error: 'Impossible de charger le nombre.',
        loading: 'Chargement…',
        pendingValidation: 'Vols à valider',
        todayFlights: 'Vols aujourd’hui',
      },
    },
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
    pilotView: {
      toggle: 'Vue pilote',
    },
    quickActions: {
      logFlight: 'Enregistrer un vol',
      openLogbook: 'Ouvrir le carnet',
    },
    reservations: {
      placeholder: 'Réservations bientôt disponibles.',
      title: 'Prochaine réservation',
    },
    sysadmin: {
      heading: 'Aperçu du système',
      tenantEnter: 'Entrer dans un club',
      tiles: {
        error: 'Impossible de charger ce nombre.',
        loading: 'Chargement…',
        totalClubs: 'Clubs',
        totalFlights: 'Vols',
        totalUsers: 'Utilisateurs',
      },
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
    copied: 'Handshake copié.',
    copy: 'Copier le handshake',
    download: 'Télécharger le fichier de handshake',
    error: 'Impossible de générer la clé. Veuillez réessayer.',
    expires: 'Valide jusqu’au {{expiresAt}}',
    headline: 'Transmettre la clé publique à l’outil d’export',
    jarPanel: {
      cta: 'Afficher l’outil d’export',
      title: 'Outil d’export requis',
    },
    loading: 'Génération de la clé…',
    pemHint:
      'À titre indicatif — fournissez à l’outil d’export le fichier de handshake téléchargé.',
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
  profile: {
    account: {
      clubId: 'Club',
      friendlyName: 'Nom affiché',
      language: 'Langue',
      notificationEmail: 'E-mail de notification',
      notificationEmailHelp:
        'Utilisé pour les notifications dans l’application. L’e-mail de connexion est géré dans Keycloak.',
      phone: 'Téléphone',
      readOnlyHint: 'Géré par ton administrateur de club.',
      save: 'Enregistrer',
      saveError: 'Échec de l’enregistrement. Réessaie.',
      saved: 'Compte enregistré.',
      username: 'Nom d’utilisateur',
    },
    noPersonBanner:
      'Aucun dossier de membre lié. Demande à ton administrateur de club de lier ton dossier de membre.',
    stub: 'Édition à venir.',
    tabs: {
      account: 'Compte',
      notifications: 'Notifications',
      personal: 'Personnel',
      pilot: 'Pilote',
    },
    title: 'Profil',
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
