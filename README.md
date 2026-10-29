# Audiothèque

Application Android (Kotlin) qui remplace le duo « synchro Drive générique + lecteur » : elle connaît les
dossiers de statut de `/AudioBook` sur Google Drive, télécharge le livre en cours, le lit hors ligne et
range les livres d'un geste — chaque geste déplace le dossier du livre dans Drive.

Implémentation de la spécification
[`Spécification — App de synchro et lecture d’audiobooks.md`](Spécification%20—%20App%20de%20synchro%20et%20lecture%20d’audiobooks.md)
(MVP, v2 et v3).

L'app travaille uniquement sur la vraie bibliothèque : elle appelle l'API Google Drive v3 depuis le
téléphone, avec le compte Google connecté. Aucun Drive simulé, aucune bibliothèque ni piste fictive
n'est livrée dans l'app.

## Démarrage rapide

Prérequis : Android Studio (JDK 21 fourni), SDK Android 36, un téléphone Android 13+ et la
configuration Google Cloud ci-dessous (sans elle, la connexion Google échoue).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS
./gradlew assembleRelease        # APK : app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # tests unitaires
adb install -r app/build/outputs/apk/release/app-release.apk
```

Au premier lancement : **Se connecter avec Google**, choisir le compte qui possède `/AudioBook`
et accepter l'autorisation Drive. La première synchro fait
l'inventaire complet des dossiers de statut et télécharge `_En écoute`.

Parcours du critère de sortie du MVP : onglet *En écoute* → ▶ *Civilisations* → écoute jusqu'au
bout → « Marquer comme écouté » → le dossier passe dans `_Ecouté` sur Drive (Synchro › Historique :
« Déplacement Drive ») sans ouvrir Drive.

## Configuration Google (vraie bibliothèque)

L'app appelle directement Google Drive API v3 depuis le téléphone : aucun serveur tiers. Google
n'accepte une app Android que si elle est déclarée dans un projet Google Cloud ; tant que ce n'est
pas fait, l'écran d'autorisation Google se referme aussitôt et l'app affiche « L'autorisation Google
Drive n'a pas abouti », avec le nom de paquet et l'empreinte SHA-1 à déclarer (bouton « Copier »).

À faire une fois, avec le compte Google qui possède `/AudioBook` (de préférence un projet personnel,
hors organisation) :

1. **Activer Google Drive API** :
   [console.cloud.google.com/apis/library/drive.googleapis.com](https://console.cloud.google.com/apis/library/drive.googleapis.com).
2. **Écran de consentement OAuth** ([Google Auth Platform](https://console.cloud.google.com/auth/overview)) :
   audience *Externe*, statut *Test* ; ajouter les comptes autorisés comme **utilisateurs test**
   (Julien, et Julie et Clément s'ils utilisent l'app). Accès aux données :
   `https://www.googleapis.com/auth/drive` (déplacer des dossiers que l'app n'a pas créés, écrire
   la progression et la pochette dans le dossier de chaque livre).
3. **ID client OAuth de type Android** ([Clients › Créer](https://console.cloud.google.com/auth/clients/create)) :
   nom de paquet `com.julienmiquel.audiotheque` et empreinte SHA-1 du certificat de signature. Les
   builds debug et release sont signés avec la clé de debug (usage personnel, APK installé à la main) :
   une seule empreinte suffit. Elle est affichée par l'app en cas d'échec, ou par :
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
   ```
4. **Facultatif — ID client de type « Application Web »** pour Credential Manager (bouton
   « Se connecter avec Google » natif). Le copier dans `local.properties` :
   ```properties
   audiotheque.webClientId=1234567890-xxxx.apps.googleusercontent.com
   ```
   Sans lui, le compte est choisi directement à l'écran d'autorisation Drive (AuthorizationClient).
5. **Facultatif — Google Images pour les pochettes introuvables.** Les pochettes viennent d'abord
   de Google Books, d'Apple Books (catalogue des livres audio) et d'Open Library, sans clé. Pour
   ajouter la recherche d'images Google en dernier recours : créer un
   [moteur de recherche programmable](https://programmablesearchengine.google.com/) réglé sur
   « Rechercher sur l'ensemble du Web » avec la **recherche d'images** activée, activer
   [Custom Search API](https://console.cloud.google.com/apis/library/customsearch.googleapis.com)
   et créer une clé d'API (100 requêtes gratuites par jour), puis :
   ```properties
   audiotheque.customSearchKey=AIza...
   audiotheque.customSearchCx=0123456789abcdef0
   ```
   Sans ces deux lignes, la source est simplement absente ; le menu de la fiche livre propose de
   toute façon « Chercher la pochette sur Google Images » (dans le navigateur) et « Choisir une
   image dans mes photos… » (Google Photos ou galerie).

Aucune réinstallation n'est nécessaire après les étapes 1 à 4 : il suffit de toucher à nouveau « Se
connecter avec Google » (la prise en compte par Google peut prendre quelques minutes).

Notes :

- En statut *En test*, Google peut demander de renouveler l'autorisation au bout de quelques jours :
   l'app affiche alors une seule fois la notification « Connexion Google à renouveler » et, dans l'app,
   un bandeau « Autorisation Google Drive à renouveler » au-dessus des onglets. Son bouton
   **Renouveler** rouvre l'écran d'autorisation Google sans se déconnecter ni rien effacer. L'app
   n'insiste pas en arrière-plan : les gestes restent en file et partent dès le renouvellement. Une
   autorisation donnée pour un autre compte est refusée.
- Une publication sur le Play Store imposerait la vérification Google du champ `drive` (restreint) :
   hors sujet pour un APK personnel (cf. questions ouvertes).
- Pour enregistrer les livres dans **[Stockage interne]/Audiobooks** comme l'app actuelle, accorder
   « Accès à tous les fichiers » (Réglages › Stockage, ou Synchro › Dossiers synchronisés). Sans cette autorisation,
   les livres vont dans le dossier de l'app (`Android/data/com.julienmiquel.audiotheque/files/Audiobooks`).

## Android Auto et vignette de reprise

L'app est un `MediaLibraryService` (Media3) : la voiture, l'Assistant et les Réglages rapides
n'ont rien à configurer, mais **un APK installé à la main n'apparaît dans Android Auto qu'en mode
développeur** : ouvrir l'app *Android Auto* sur le téléphone (Paramètres › Appareils connectés ›
Android Auto), toucher dix fois le numéro de *Version* pour activer les paramètres développeur,
puis ⋮ › *Paramètres développeur* › cocher **Sources inconnues**. Relancer Android Auto :
« Audiothèque » figure dans le lanceur de la voiture.

| Où | Ce que propose l'app |
| :--- | :--- |
| Lanceur Android Auto | Onglets **En écoute** et **Plus tard** (ce dernier seulement s'il a de quoi écouter) : grille de pochettes avec barre d'avancement et badge « téléchargé » ; seuls les livres ayant des pistes sur le téléphone sont proposés — la lecture reste hors ligne, jamais en streaming |
| Un livre | Toucher reprend la lecture à la position gardée (après relecture de la position Drive, 4 s au plus) ; le chevron ouvre les **chapitres groupés par partie**, avec l'avancement de chacun |
| Lecture en cours | **« Piste 4 / 51 » (ou « Chapitre 4 ») puis le titre du livre** — jamais le nom de fichier ni l'auteur en première ligne (l'auteur reste en 3ᵉ ligne / album) ; pochette ; **⏮/⏭ = chapitre précédent/suivant** (boutons standards, visibles partout), puis **−30 s / +30 s** (pas réglable) et **Vitesse** (un appui : 1,0 → 1,2 → 1,5 → 2,0 → 0,8×, mémorisée par livre) en boutons supplémentaires ; file d'attente = chapitres |
| Recherche et voix | « Écoute *Civilisations* », « Lis le livre de *Laurent Binet* », « Reprends mon livre » : titre, auteur ou voix, mots de remplissage ignorés ; sans mots, le dernier livre écouté |
| Réglages rapides, écran verrouillé | Pendant l'écoute : « Piste 4 / 51 » / titre du livre, ⏮ ▶ ⏭ −30 +30. Après l'arrêt de l'app ou un redémarrage, la **vignette de reprise** affiche le dernier livre écouté avec sa pochette et où il reprend (« Piste 4 / 51 · 42 % »). Elle démarre aussitôt à la position locale, puis saute à celle de l'autre téléphone si Drive en a une plus récente (message « Position reprise depuis… » dans l'app) |
| Montre Wear OS, casque Bluetooth, autoradio | ▶/⏸ et ⏮/⏭ (chapitres) fonctionnent sur tous ces contrôleurs : les sauts de 30 s ne prennent pas la place des boutons standards (sinon Media3 retire ⏮/⏭ de la session système et la montre n'a plus qu'un ▶ figé). Le bouton ▶ relance le dernier livre même si le service a été arrêté ; pochette et titres via AVRCP |

Les pochettes sont servies aux autres processus (voiture, System UI) par un fournisseur de
contenu en lecture seule, non exporté : le service accorde le droit de lecture à chaque application
qui se connecte ([CoverArtProvider.kt](player/src/main/java/com/julienmiquel/audiotheque/player/CoverArtProvider.kt)).

## Préréglages audio et confort d'écoute

Lecteur › **Son** (ou Réglages › Lecture) : un traitement du signal appliqué à la lecture, au choix
**pour tous les livres** ou **mémorisé livre par livre** (synchronisé sur Drive entre vos appareils),
aussi en voiture et sur la montre. Il s'insère dans la chaîne audio d'ExoPlayer avant le changement de
vitesse ([PresetAudioProcessor.kt](player/src/main/java/com/julienmiquel/audiotheque/player/audio/PresetAudioProcessor.kt)),
change sans coupure pendant l'écoute (fondu de 15 ms) et repart à zéro à chaque saut. Les blocs
([Dsp.kt](player/src/main/java/com/julienmiquel/audiotheque/player/audio/Dsp.kt)) sont en Kotlin pur,
assemblés par préréglage dans [PresetChain.kt](player/src/main/java/com/julienmiquel/audiotheque/player/audio/PresetChain.kt).

| Préréglage | Chaîne | Pour |
| :--- | :--- | :--- |
| Son d'origine | — | Le fichier tel quel |
| Volume constant | Compression 2,5:1 à −18 dBFS → normalisation automatique vers −18 dBFS RMS (+14 dB au plus, figée sous −55 dBFS) → limiteur −1 dB | Même niveau d'un chapitre et d'un livre à l'autre |
| Voix renforcée | Chaîne vocale « Netophonix » : coupe-bas 80 Hz → présence +3 dB à 3,5 kHz (Q 0,7) → compression 3:1 à −20 dBFS (5 ms / 50 ms) → normalisation vers −20 dBFS → limiteur | Voiture, bruit : articulation plus nette |
| Niveau très bas | Compression 4:1 à −30 dBFS → normalisation vers −20 dBFS → limiteur → **−12 dB** en sortie | Écoute au casque à très faible volume (nuit) : les murmures restent audibles, le volume minimal devient vraiment bas |
| Binaural (casque) | Chaîne vocale, puis la voix (mono) placée **dans une pièce** : modèle de tête de Brown & Duda (délai interaural de Woodworth, ombre de la tête par oreille), six premières réflexions filtrées, réverbération de Schroeder, limiteur | Son « hors de la tête », moins fatigant au casque ; **position de la voix** (−30° à +30°) et **acoustique** (*Intime*, *Petite pièce*, *Salon*) réglables |

Confort d'écoute complémentaire :
- **Saut des silences** (`skipSilenceEnabled`) activable d'un geste dans *Son* ou *Réglages › Lecture*.
- **Minuterie de sommeil avancée** : durées prédéfinies, durée libre (5 à 120 min), arrêt en fin de chapitre, **fondu sonore progressif de 10 s** avant la pause et **secousse du téléphone (+10 min)** active uniquement pendant la minuterie (et 60 s après l'arrêt automatique).
- **Recul intelligent à la reprise** : recul adapté à la durée d'interruption (0 s sous 2 s, 2 s sous 1 min, 5 s sous 10 min, 10 s sous 1 h, 20 s sous 24 h, 30 s au-delà).
- **Positions récentes (annuler un saut)** : historique automatique avant chaque saut de chapitre, déplacement du curseur, signet ou minuterie.
- **Chapitres intégrés `.m4b` / `.m4a` et ID3 `CHAP`** : lecture directe des boîtes ISO BMFF (`chpl` Nero et piste texte QuickTime `tref/chap`, même lorsque `moov` est en fin de fichier) et découpage par chapitre dans le lecteur et Android Auto.

## Multi-utilisateurs et partage familial

L'app prend en charge plusieurs auditeurs (ex. Julien, Julie, Clément) aussi bien sur **des comptes Google distincts** ayant accès au dossier partagé `/AudioBook` que sur **un même compte Google** (tablette familiale, second téléphone ou mode test OAuth) :

- **Profils d'auditeurs & détection automatique sur Drive** : Réglages › *Auditeurs & Partage familial* permet d'ajouter un auditeur en un geste, de détecter automatiquement les fichiers `.audiotheque-progress-<profil>.json` déjà présents sur Google Drive, et d'afficher une puce de bascule rapide en tête de la Bibliothèque.
- **Mode familial sans conflit de dossiers (`PERSONAL_FAMILY`)** : activé automatiquement dès qu'un second auditeur est présent (ou au choix dans Réglages). Les gestes *Commencer*, *Plus tard*, *Marquer écouté* ou *Archiver* mettent à jour le **statut personnel** de l'auditeur dans `.audiotheque-progress-<profil>.json` et lancent le téléchargement hors ligne de ses livres en écoute, **sans déplacer les dossiers `_Nouveaux` / `_En écoute` / `_Ecouté` des autres membres**. Le déplacement physique dans Drive reste accessible à tout moment via Fiche livre › ⋮ › *« Déplacer le dossier dans Drive… »*.
- **Signets synchronisés par auditeur** : chaque signet ajouté ou supprimé est isolé par profil et synchronisé entre appareils dans `.audiotheque-progress-<profil>.json` (fusion élément par élément avec pierres tombales).
- **Corrections partagées pour toute la famille (`.audiotheque-meta.json`)** : lorsqu'un membre corrige un titre, un auteur ou un narrateur, confirme un découpage ou masque un dossier parasite, la correction est propagée aux autres appareils via `.audiotheque-meta.json`.
- **Épinglage hors ligne à la demande (« Garder sur ce téléphone »)** : n'importe quel livre peut être téléchargé intégralement et protégé du nettoyage automatique sur un téléphone donné, quel que soit son statut ou son dossier Drive.

## Architecture

Trois modules métier partagent une base Room ; **seul `:sync` parle à Drive**, ce qui rend les gestes
utilisables hors ligne (file d'opérations rejouée au retour du réseau).

```mermaid
flowchart LR
    subgraph APP[":app · Interface Compose M3"]
        UI["Bibliothèque, Fiche livre, Lecteur, Synchro, Réglages"]
        AUTH["Credential Manager + AuthorizationClient"]
    end
    subgraph LIB[":library · Bibliothèque et statuts"]
        GEST["Gestes de statut + Annuler 10 s"]
        REPO["Recherche, Lu par, doublons, corrections"]
    end
    subgraph PLAY[":player · Lecture Media3"]
        SVC["MediaLibraryService : notification, casque, Android Auto"]
    end
    subgraph SYNC[":sync · Moteur de synchro, seul accès Drive"]
        ENG["SyncEngine + WorkManager"]
        API["Google Drive API v3 (REST)"]
    end
    DB[("Room :core · livres, pistes, progression, file d'opérations, historique")]
    GD[("Google Drive · /AudioBook (pistes, cover.jpg, .audiotheque-progress-<compte>.json)")]
    FS[("Stockage interne / Audiobooks")]

    UI --> GEST
    UI --> REPO
    UI --> SVC
    GEST -- "opération en file" --> DB
    GEST -- "demande de synchro" --> ENG
    REPO --> DB
    SVC -- "position toutes les 10 s" --> DB
    SVC -- "position : envoi à la pause, relecture avant lecture" --> ENG
    SVC -- "lecture hors ligne" --> FS
    ENG --> DB
    ENG --> API
    API --> GD
    ENG -- "part, md5, renommage" --> FS
    AUTH -. "jeton drive" .-> API
```

| Rendu exact du diagramme | Vue d'ensemble |
| :---: | :---: |
| ![Diagramme Mermaid](README_architecture_mermaid.png) | ![Infographie](README_architecture_infographic.jpg) |

| Module | Rôle | Fichiers clés |
| :--- | :--- | :--- |
| `:core` | Base Room v8 (livres + épinglage hors ligne, pistes, chapitres intégrés, progression + statut personnel, sessions d'écoute, signets par auditeur, historique, synchros, file d'opérations, paires), réglages DataStore & profils d'auditeurs, découpage et normalisation des noms de dossiers (`Titre - AUTEUR`), détection des séries et tomes (`Fondation · T.1`), filtrage des noms de pistes redondants, lecture des premiers Ko d'un mp3/m4b (ID3, CHAP, ISO BMFF, débit), mise en forme française | [Entities.kt](core/src/main/java/com/julienmiquel/audiotheque/core/db/Entities.kt), [SettingsRepository.kt](core/src/main/java/com/julienmiquel/audiotheque/core/settings/SettingsRepository.kt), [FolderNameParser.kt](core/src/main/java/com/julienmiquel/audiotheque/core/parse/FolderNameParser.kt), [Mp3Probe.kt](core/src/main/java/com/julienmiquel/audiotheque/core/parse/Mp3Probe.kt), [Mp4Probe.kt](core/src/main/java/com/julienmiquel/audiotheque/core/parse/Mp4Probe.kt) |
| `:library` | Gestes de statut (mode dossiers Drive ou mode familial personnel sans déplacement Drive) et renommage normalisé (`Titre - AUTEUR`) + « Annuler » 10 s, épinglage hors ligne « Garder sur ce téléphone », corrections titre/auteur/voix partagées, recommandations locales « À écouter ensuite », recherche (titre, auteur, série, voix), tri multicritère, filtres « Hors ligne » et « Lu par », statistiques d'écoute et séries quotidiennes par auditeur, export/import JSON v2, doublons et corbeille Drive, pochette et résumé en ligne | [Library.kt](library/src/main/java/com/julienmiquel/audiotheque/library/Library.kt), [ListeningStats.kt](library/src/main/java/com/julienmiquel/audiotheque/library/ListeningStats.kt), [BackupManager.kt](library/src/main/java/com/julienmiquel/audiotheque/library/BackupManager.kt), [BookRecommender.kt](library/src/main/java/com/julienmiquel/audiotheque/library/BookRecommender.kt), [GoogleBooks.kt](library/src/main/java/com/julienmiquel/audiotheque/library/GoogleBooks.kt) |
| `:sync` | Client Google Drive API v3 (REST), inventaire complet puis flux de modifications, file d'opérations (déplacements, renommages, corbeille) avec nouvelles tentatives espacées, téléchargements (3 en parallèle, `.part`, Range, taille + md5), décompression et inspection hors ligne des archives `.zip`, nettoyage par statut (protégeant les livres épinglés et ceux en écoute pour un autre profil de l'appareil), synchro v3 par auditeur (progression, statut personnel, signets) et détection des profils Drive, synchro familiale des métadonnées (`.audiotheque-meta.json`), dépôt des pochettes téléchargées, WorkManager | [SyncEngine.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/SyncEngine.kt), [ProgressSync.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/ProgressSync.kt), [MetadataSync.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/MetadataSync.kt), [ZipExtractor.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/ZipExtractor.kt), [Transfers.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/Transfers.kt), [RestDriveService.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/drive/RestDriveService.kt) |
| `:player` | Media3 ExoPlayer + MediaLibraryService isolé par profil d'auditeur : reprise intelligente selon la durée d'absence, sauvegarde toutes les 10 s et à chaque pause, enregistrement précis des sessions d'écoute actives, position envoyée dans Drive à la pause et relue avant la lecture, saut réglable et historique des sauts, vitesse et préréglage audio par livre, saut des silences, minuterie avancée (fondu 10 s + secousse +10 min), fin du livre, notification, écran verrouillé, casque ; catalogue Android Auto filtré sur le profil actif, vignette de reprise du système, pochettes servies par un fournisseur de contenu ; préréglages audio dans la chaîne ExoPlayer | [PlaybackService.kt](player/src/main/java/com/julienmiquel/audiotheque/player/PlaybackService.kt), [AutoCatalog.kt](player/src/main/java/com/julienmiquel/audiotheque/player/AutoCatalog.kt), [Dsp.kt](player/src/main/java/com/julienmiquel/audiotheque/player/audio/Dsp.kt), [CoverArtProvider.kt](player/src/main/java/com/julienmiquel/audiotheque/player/CoverArtProvider.kt), [PlayerConnection.kt](player/src/main/java/com/julienmiquel/audiotheque/player/PlayerConnection.kt) |
| `:app` | Interface Compose Material 3 (thème sombre à accent vert, thème clair en option), sélecteur rapide d'auditeur et gestion familiale, navigation à 3 onglets + **mini-lecteur persistant** (Bibliothèque, Fiche livre, Synchro, Réglages, Statistiques), vue liste ou grille de pochettes, écran **Statistiques d'écoute**, sauvegarde/restauration JSON, widget d'écran d'accueil Android, connexion Google | [AudiothequeRoot.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/AudiothequeRoot.kt), [LibraryScreen.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/library/LibraryScreen.kt), [SettingsScreen.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/settings/SettingsScreen.kt), [AudiothequeWidgetProvider.kt](app/src/main/java/com/julienmiquel/audiotheque/widget/AudiothequeWidgetProvider.kt), [AudiothequeApp.kt](app/src/main/java/com/julienmiquel/audiotheque/AudiothequeApp.kt) |

### Correspondance avec la spécification

| Spécification | Implémentation |
| :--- | :--- |
| Statut = dossier parent ou statut personnel familial | En mode `SHARED_DRIVE`, `StatusGestures` écrit `MOVE_BOOK` en file et `OperationExecutor` déplace le dossier sur Drive. En mode `PERSONAL_FAMILY`, chaque auditeur change librement le statut de ses livres dans son profil sans déplacer les dossiers Drive partagés ; `moveDriveFolder` reste disponible pour le rangement physique |
| Bandeau « Annuler » 10 s | Snackbar `SnackbarDuration.Long` (10 s) après chaque geste ou renommage ([AudiothequeRoot.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/AudiothequeRoot.kt)). « Annuler » remet le livre dans son statut personnel, son dossier ou son nom de départ et rétablit la progression modifiée par le geste |
| Drive fait foi | Flux de modifications : un dossier déplacé ou renommé ailleurs met à jour l'état local ; un geste encore en file n'écrase pas ce changement externe : il est abandonné et signalé dans l'historique |
| Un échec ne modifie pas l'état local | Statut et nom confirmés distincts des valeurs en attente ; refus définitif → opération abandonnée (un « Marquer écouté » refusé ne laisse pas « terminé ») ; nouvel essai espacé (30 s, 1 min, 2 min… 1 h), jamais en boucle si l'autorisation Google est à renouveler |
| `_En écoute` et livres épinglés descendent en entier (dossiers et `.zip`) | `Downloader.plan()` & `Downloader.zipPlan()` téléchargent tous les livres en écoute pour le profil actif ainsi que ceux marqués « Garder sur ce téléphone » (`pinnedOffline`) ; les archives `.zip` sont téléchargées avec reprise HTTP `Range` + vérification MD5 et décompressées hors ligne ; Nouveaux : métadonnées (+ 1ʳᵉ piste si « Aperçu ») ; Plus tard 7 j, Écouté 24 h ([CleanupPolicy.kt](core/src/main/java/com/julienmiquel/audiotheque/core/model/CleanupPolicy.kt)) |
| Renommage normalisé sur Drive (optionnel) | Fiche livre › ⋮ › « Normaliser le nom sur Drive… » (ou case à cocher dans « Corriger le titre ou l'auteur ») : renomme le dossier ou le `.zip` selon `Titre - AUTEUR` via la file d'opérations hors ligne (`RENAME_BOOK`), avec « Annuler » 10 s |
| Suggestions « À écouter ensuite » | `BookRecommender` : classement local sur l'appareil (affinité auteur/voix avec les livres terminés ou en cours, livres commencés dans *Plus tard*, durée proche de vos habitudes, disponibilité hors ligne) affiché en tête de *Nouveaux* et à la fin d'un livre |
| Ergonomie bibliothèque : tri, grille, hors-ligne, séries | Barre rapide dans la Bibliothèque : puce **Auditeur** (si multi-profils), filtre **Hors ligne**, filtre **Voix**, **tri multicritère** (par défaut, écoutés récemment, ajout récent, titre/série A→Z, auteur A→Z, durée croissante/décroissante), bascule **liste / grille de pochettes**, et reconnaissance automatique des **séries et numéros de tome** |
| Mini-lecteur persistant | `MiniPlayerBar` ancré au-dessus de la barre de navigation sur tous les écrans hors Lecteur : barre d'avancement en direct, pochette, titre, chapitre et pourcentage, recul rapide (−30 s) et lecture/pause |
| Statistiques d'écoute & Sauvegarde JSON | `StatsScreen` isolé par profil d'auditeur (temps d'écoute aujourd'hui / 7 j / 30 j / total, séries de jours consécutifs actuelle et record, histogramme interactif 7 j / 30 j, livres terminés, top livres, auteurs et narrateurs) ; `BackupManager` v2 (export et restauration JSON non destructifs des progressions, statuts personnels, épinglages hors ligne, signets par auditeur, corrections et sessions d'écoute) |
| Widget d'écran d'accueil | `AudiothequeWidgetProvider` (4×1 redimensionnable) : pochette, titre, chapitre et pourcentage du profil actif, barre d'avancement, recul, lecture/pause et avance rapide sans réveiller le service en arrière-plan |
| Archives, méditations, racine : à l'ouverture | Noms des livres listés à l'inventaire (une requête par dossier, pour que la recherche couvre toute la bibliothèque) ; pistes, durées et pochettes lues à l'ouverture de l'onglet |
| Inventaire complet à la 1ʳᵉ synchro puis chaque semaine | `LibraryScanner.scan` (`changes.getStartPageToken` / `changes.list` entre-temps) |
| 6 h, Wi-Fi seul, batterie non faible ; synchro au lancement et à chaque geste | `SyncScheduler` (WorkManager), réglable dans Synchro › Dossiers synchronisés. En « Wi-Fi uniquement », sur réseau mesuré, téléchargements, échantillons audio et pochettes attendent le Wi-Fi : une synchro repart à son retour |
| 3 téléchargements, reprise, ordre depuis la position, `.part` + taille + md5, espace + 500 Mo | `Downloader` ; les pistes des livres épinglés et de `_En écoute` sont listées et téléchargées avant les métadonnées du reste de la bibliothèque |
| Historique 30 jours filtrable par livre | `HistoryLogger`, Synchro › Historique |
| Lecteur : reprise, chapitres par partie, ±30 s, vitesse 0,8–2,0, minuterie, signets, avancement | [PlayerScreen.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/player/PlayerScreen.kt), `PlaybackService` ; masquage automatique des noms de fichiers redondants (`01 - Civilisations.mp3`, `Track 03`) au profit de vrais titres de chapitres ; préréglages audio (bouton Son, mémorisables par livre) et barre du chapitre en cours déplaçable au-dessus de la barre globale en lecture seule |
| Position conservée dans tous les statuts | « Réécouter » repart du début d'un livre fini, garde la position sinon |
| Fin du livre : proposer « Marquer comme écouté » sans rien déplacer seul | Dialogue (avec suggestions « À écouter ensuite ») + carte persistante dans le lecteur et la fiche |
| Progression, statut personnel et signets par auditeur + métadonnées familiales | `ProgressSync` v3 synchronise `.audiotheque-progress-<profil>.json` (position LWW, statut personnel indépendant, signets fusionnés par `syncId` avec pierres tombales) ; `MetadataSync` partage les corrections de titre/auteur/narrateur et le masquage via `.audiotheque-meta.json` |
| Pochette et résumé | `Enricher` : Google Books, Apple Books, Open Library et Google Images (si configuré). La pochette est téléchargée une fois sur le téléphone et déposée dans le dossier Drive du livre (`cover.jpg`) pour toute la famille |
| Durée exacte après téléchargement, sinon estimée par le débit | `MetadataProber.probe` (premiers Ko de la 1ʳᵉ piste) puis `MediaMetadataRetriever` |
| Voix lue dans les tags ID3 | `Id3Tags.narrator()` (TXXX, « lu par », compositeur) |
| Badge doublon | Même titre, sauf si les deux auteurs sont connus et diffèrent. La fiche liste les exemplaires et permet de mettre un doublon à la corbeille Drive (restaurable 30 jours, « Annuler » 10 s) |
| « Mettre de côté » (fiche livre, appui long dans la bibliothèque) | Lecture mise en pause, position gardée (« Reprendre » repart exactement là), avancement noté, pistes conservées 7 jours (réglable) avant nettoyage |
| Accessibilité | Descriptions TalkBack (interrupteurs, badge de synchro, boutons de vitesse, mini-lecteur, widget), erreurs signalées en toutes lettres, titres de section, tailles en `sp`, palettes M3 contrastées |

### Réponses retenues aux questions ouvertes (modifiables)

| Question | Choix de l'app |
| :--- | :--- |
| Julie et Clément | Pris en charge de bout en bout : comptes Google séparés ou profils d'auditeurs nommés sur un même compte, progression/signets/statistiques isolés par auditeur, mode familial sans conflit de dossiers Drive (`PERSONAL_FAMILY`), et corrections de métadonnées partagées (`.audiotheque-meta.json`) |
| Plusieurs livres dans `_En écoute` | Autorisé ; tous sont téléchargés (ainsi que les livres « Gardés sur ce téléphone »), en commençant par le dernier écouté |
| Dossiers qui ne sont pas des livres | Fiche livre › « Ce n'est pas un livre (masquer) » (partagé avec la famille), ou déplacement dans `_archives` |
| Délais de nettoyage | 7 jours et 24 h par défaut, réglables (Réglages › Nettoyage) ; jamais nettoyé si « Gardé sur ce téléphone » ou en écoute par un autre profil de l'appareil |
| Play Store ou APK personnel | APK personnel, accès Drive en mode test (voir ci-dessus) |
| *Les guerriers de l'hiver* | Sa progression (46 %) est conservée ; « Déplacer dans Drive… » `_A écouter plus tard` ou « Réécouter » (qui reprend à 46 %) depuis la fiche |
| Déconnexion | La progression est d'abord écrite dans les dossiers Drive des livres, puis la bibliothèque locale et les pistes sont effacées ; progression (par profil), sessions d'écoute et signets restent sur le téléphone |

## Tests

```bash
./gradlew testDebugUnitTest
```

Suites de tests unitaires (JUnit, Robolectric, MockWebServer). Les doubles de test (Drive en mémoire,
serveur HTTP local) et les fichiers de `testdata/audio` n'existent que dans les tests : ils ne sont
pas compilés dans l'app, qui ne parle qu'à la vraie API Google Drive.

| Suite | Couverture |
| :--- | :--- |
| `core` · `FolderNameParserTest`, `TrackNameParserTest` | Toutes les anomalies de noms de la spécification, normalisation `Titre - AUTEUR`, détection des séries et tomes, filtrage des noms de pistes redondants, motif « 091_P4_04_… », ordre naturel |
| `core` · `Mp3ProbeTest`, `Mp4ProbeTest`, `ProgressMathTest` | Tags ID3, chapitres `CHAP` et boîtes ISO BMFF (`chpl`, `tref/chap`), pochette intégrée, durée par le débit, recul intelligent, formats français |
| `library` · `StatusGesturesTest`, `FamilyModeGesturesTest`, `BookRecommenderTest`, `ListeningStatsTest`, `BackupManagerTest` | Gestes et renommages mis en file, mode familial sans déplacement Drive et isolation inter-profils (statuts, signets, épinglage hors ligne, « Annuler »), recommandations locales, tri multicritère et séries, séries de jours d'écoute et histogrammes 7j/30j, export/restauration JSON v2, doublons et corbeille Drive |
| `library` · `CoverEnrichmentTest` | Pochettes contre un serveur HTTP local : correspondance stricte sans titre confirmé, Google Books injoignable → Apple Books (pochette déposée dans Drive), pochette d'un autre livre refusée puis recherche relancée après confirmation du titre, image morte → source suivante, hors ligne et portail captif sans abandon, Google Images en dernier recours (format pochette), choix des dossiers qui méritent une recherche |
| `sync` · `SyncEngineTest`, `MultiUserSyncTest`, `ZipExtractorTest` | Moteur contre un double de test de Drive : inventaire, téléchargements vérifiés, décompression `.zip`, renommage Drive normalisé, nettoyage 24 h / protection `pinnedOffline` et multi-profils, synchro `ProgressSync` v3 (rétrocompatibilité v2, fusion granulaire position / statut personnel / signets avec pierres tombales, découverte des profils sur Drive), synchro familiale `MetadataSync` (`.audiotheque-meta.json`), progression inter-appareils, doublon mis à la corbeille Drive |
| `sync` · `RestDriveServiceTest` | Chaque appel du tableau Drive API v3 contre un serveur HTTP local (MockWebServer), jeton renouvelé, limites de débit |
| `player` · `PlayerLogicTest`, `AutoCatalogTest`, `PresetChainTest` | Identifiants média, chapitres intégrés et par partie, minuterie et fondu, bornes de vitesse, catalogue Android Auto et recherche vocale, chaîne DSP complète (coupe-bas, présence, compresseur, normalisation, niveau très bas, binaural) |

## Hors périmètre

Envoi de livres depuis le téléphone, fichiers Audible (AAX), iOS.
