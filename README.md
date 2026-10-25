# Audiothèque

Application Android (Kotlin) qui remplace le duo « synchro Drive générique + lecteur » : elle connaît les
dossiers de statut de `/AudioBook` sur Google Drive, télécharge le livre en cours, le lit hors ligne et
range les livres d'un geste — chaque geste déplace le dossier du livre dans Drive.

Implémentation de la spécification
[`Spécification — App de synchro et lecture d’audiobooks.md`](Spécification%20—%20App%20de%20synchro%20et%20lecture%20d’audiobooks.md)
(MVP et v2).

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
et accepter les deux autorisations (Drive et espace caché de l'app). La première synchro fait
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
   `https://www.googleapis.com/auth/drive` (déplacer des dossiers que l'app n'a pas créés) et
   `https://www.googleapis.com/auth/drive.appdata` (progression).
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

Aucune réinstallation n'est nécessaire après ces étapes : il suffit de toucher à nouveau « Se
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
    GD[("Google Drive · /AudioBook + appDataFolder")]
    FS[("Stockage interne / Audiobooks")]

    UI --> GEST
    UI --> REPO
    UI --> SVC
    GEST -- "opération en file" --> DB
    GEST -- "demande de synchro" --> ENG
    REPO --> DB
    SVC -- "position toutes les 10 s" --> DB
    SVC -- "lecture hors ligne" --> FS
    ENG --> DB
    ENG --> API
    API --> GD
    ENG -- "part, md5, renommage" --> FS
    AUTH -. "jeton drive + drive.appdata" .-> API
```

| Rendu exact du diagramme | Vue d'ensemble |
| :---: | :---: |
| ![Diagramme Mermaid](README_architecture_mermaid.png) | ![Infographie](README_architecture_infographic.jpg) |

| Module | Rôle | Fichiers clés |
| :--- | :--- | :--- |
| `:core` | Base Room (livres, pistes, progression, signets, historique, synchros, file d'opérations, paires), réglages DataStore, découpage des noms de dossiers et de pistes, lecture des premiers Ko d'un mp3 (ID3, débit), mise en forme française | [Entities.kt](core/src/main/java/com/julienmiquel/audiotheque/core/db/Entities.kt), [FolderNameParser.kt](core/src/main/java/com/julienmiquel/audiotheque/core/parse/FolderNameParser.kt), [TrackNameParser.kt](core/src/main/java/com/julienmiquel/audiotheque/core/parse/TrackNameParser.kt), [Mp3Probe.kt](core/src/main/java/com/julienmiquel/audiotheque/core/parse/Mp3Probe.kt) |
| `:library` | Gestes de statut mis en file + « Annuler », déplacement vers n'importe quel statut, corrections titre/auteur/voix (le dossier n'est jamais renommé), recherche, filtre « Lu par », doublons, Google Books | [Library.kt](library/src/main/java/com/julienmiquel/audiotheque/library/Library.kt), [GoogleBooks.kt](library/src/main/java/com/julienmiquel/audiotheque/library/GoogleBooks.kt) |
| `:sync` | Client Google Drive API v3 (REST), inventaire complet puis flux de modifications, file de déplacements avec nouvelles tentatives espacées, téléchargements (3 en parallèle, `.part`, Range, taille + md5), nettoyage par statut, progression dans `appDataFolder`, WorkManager | [SyncEngine.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/SyncEngine.kt), [Transfers.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/engine/Transfers.kt), [RestDriveService.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/drive/RestDriveService.kt), [SyncWork.kt](sync/src/main/java/com/julienmiquel/audiotheque/sync/work/SyncWork.kt) |
| `:player` | Media3 ExoPlayer + MediaLibraryService : reprise −5 s, sauvegarde toutes les 10 s et à chaque pause, saut réglable, vitesse par livre, minuterie, fin du livre, notification, écran verrouillé, casque, Android Auto | [PlaybackService.kt](player/src/main/java/com/julienmiquel/audiotheque/player/PlaybackService.kt), [PlayerConnection.kt](player/src/main/java/com/julienmiquel/audiotheque/player/PlayerConnection.kt) |
| `:app` | Interface Compose Material 3 (thème sombre à accent vert, thème clair en option), navigation à 3 onglets, connexion Google | [AudiothequeRoot.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/AudiothequeRoot.kt), [AudiothequeApp.kt](app/src/main/java/com/julienmiquel/audiotheque/AudiothequeApp.kt) |

### Correspondance avec la spécification

| Spécification | Implémentation |
| :--- | :--- |
| Statut = dossier parent ; un geste = un déplacement | `StatusGestures` écrit l'opération en base et le statut « en attente » ; `OperationExecutor` appelle `files.update` (`addParents` / `removeParents`) |
| Bandeau « Annuler » 10 s | Snackbar `SnackbarDuration.Long` (10 s) après chaque geste ([AudiothequeRoot.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/AudiothequeRoot.kt)). « Annuler » remet le livre dans son dossier de départ et rétablit la progression modifiée par le geste, même si le déplacement était déjà en cours |
| Drive fait foi | Flux de modifications : un dossier déplacé ailleurs met à jour le statut (« Statut mis à jour depuis Drive ») ; un geste encore en file n'écrase pas ce déplacement : il est abandonné et signalé dans l'historique |
| Un échec ne modifie pas l'état local | Statut confirmé distinct du statut en attente ; refus définitif → geste abandonné (un « Marquer écouté » refusé ne laisse pas « terminé ») ; nouvel essai espacé (30 s, 1 min, 2 min… 1 h), jamais en boucle si l'autorisation Google est à renouveler |
| Seul `_En écoute` descend en entier | `Downloader.plan()` ; Nouveaux : métadonnées (+ 1ʳᵉ piste si « Aperçu ») ; Plus tard 7 j, Écouté 24 h ([CleanupPolicy.kt](core/src/main/java/com/julienmiquel/audiotheque/core/model/CleanupPolicy.kt), délais réglables, nettoyage programmé à l'échéance). Rien n'est supprimé avant que Drive ait confirmé le déplacement, ni dans les 10 min qui suivent |
| Archives, méditations, racine : à l'ouverture | Noms des livres listés à l'inventaire (une requête par dossier, pour que la recherche couvre toute la bibliothèque) ; pistes, durées et pochettes lues à l'ouverture de l'onglet |
| Inventaire complet à la 1ʳᵉ synchro puis chaque semaine | `LibraryScanner.scan` (`changes.getStartPageToken` / `changes.list` entre-temps) |
| 6 h, Wi-Fi seul, batterie non faible ; synchro au lancement et à chaque geste | `SyncScheduler` (WorkManager), réglable dans Synchro › Dossiers synchronisés. En « Wi-Fi uniquement », sur réseau mesuré, téléchargements, échantillons audio et pochettes attendent le Wi-Fi : une synchro repart à son retour |
| 3 téléchargements, reprise, ordre depuis la position, `.part` + taille + md5, espace + 500 Mo | `Downloader` ; les pistes de `_En écoute` sont listées et téléchargées avant les métadonnées du reste de la bibliothèque |
| Historique 30 jours filtrable par livre | `HistoryLogger`, Synchro › Historique |
| Lecteur : reprise, chapitres par partie, ±30 s, vitesse 0,8–2,0, minuterie, signets, avancement | [PlayerScreen.kt](app/src/main/java/com/julienmiquel/audiotheque/ui/player/PlayerScreen.kt), `PlaybackService` ; ouvrir un livre sans l'écouter n'enregistre rien (pas de recul de 5 s cumulé) |
| Position conservée dans tous les statuts | « Réécouter » repart du début d'un livre fini, garde la position sinon |
| Fin du livre : proposer « Marquer comme écouté » sans rien déplacer seul | Dialogue + carte persistante dans le lecteur et la fiche (aussi avec la minuterie « fin du chapitre » sur le dernier chapitre) |
| Progression par utilisateur dans `appDataFolder` | `ProgressSync` : un JSON par livre (`progress-<ID du dossier>.json`), le plus récent l'emporte ; dates de Drive (pas l'horloge du téléphone) |
| Durée exacte après téléchargement, sinon estimée par le débit | `MetadataProber.probe` (premiers Ko de la 1ʳᵉ piste) puis `MediaMetadataRetriever` |
| Voix lue dans les tags ID3 | `Id3Tags.narrator()` (TXXX, « lu par », compositeur) |
| Badge doublon | Même titre, sauf si les deux auteurs sont connus et diffèrent |
| Accessibilité | Descriptions TalkBack (interrupteurs, badge de synchro, boutons de vitesse), erreurs signalées en toutes lettres, titres de section, tailles en `sp`, palettes M3 contrastées |

### Réponses retenues aux questions ouvertes (modifiables)

| Question | Choix de l'app |
| :--- | :--- |
| Julie et Clément | Progression propre à chaque compte (appData) ; les dossiers de statut restent partagés. Une paire par personne peut être ajoutée (paires illimitées) |
| Plusieurs livres dans `_En écoute` | Autorisé ; tous sont téléchargés, en commençant par le dernier écouté |
| Dossiers qui ne sont pas des livres | Fiche livre › « Ce n'est pas un livre (masquer) », ou « Déplacer vers… » `_archives` |
| Délais de nettoyage | 7 jours et 24 h par défaut, réglables (Réglages › Nettoyage) |
| Play Store ou APK personnel | APK personnel, accès Drive en mode test (voir ci-dessus) |
| *Les guerriers de l'hiver* | Sa progression (46 %) est conservée ; « Déplacer vers… » `_A écouter plus tard` ou « Réécouter » (qui reprend à 46 %) depuis la fiche |
| Déconnexion | La progression est d'abord écrite dans l'espace caché du compte, puis la bibliothèque locale et les pistes sont effacées ; progression (par compte) et signets restent sur le téléphone |

## Tests

```bash
./gradlew testDebugUnitTest
```

68 tests unitaires (JUnit, Robolectric, MockWebServer). Les doubles de test (Drive en mémoire,
serveur HTTP local) et les fichiers de `testdata/audio` n'existent que dans les tests : ils ne sont
pas compilés dans l'app, qui ne parle qu'à la vraie API Google Drive.

| Suite | Couverture |
| :--- | :--- |
| `core` · `FolderNameParserTest`, `TrackNameParserTest` | Toutes les anomalies de noms de la spécification, motif « 091_P4_04_… », ordre naturel |
| `core` · `Mp3ProbeTest`, `ProgressMathTest` | Tags ID3 et pochette d'un fichier mp3, durée par le débit, pourcentage et temps restant, formats français |
| `library` · `StatusGesturesTest` | Gestes mis en file, « Annuler » (dossier de départ, progression rétablie), gestes hors ligne, position gardée, « Réécouter » d'un livre pas fini, corrections, recherche, « Lu par », doublons titre + auteur |
| `sync` · `SyncEngineTest` | Moteur contre un double de test de Drive peuplé avec l'inventaire de la spécification : inventaire, téléchargements vérifiés, Marquer écouté → `_Ecouté` puis nettoyage 24 h, rien de supprimé avant confirmation de Drive (+10 min), Drive fait foi (y compris face à un geste en file), « Annuler » pendant un déplacement, hors ligne, refus 403, autorisation expirée sans boucle (bandeau « Renouveler », geste envoyé après renouvellement), Wi-Fi uniquement sur réseau mesuré, espace insuffisant, reprise Range, livre en cours de lecture jamais retiré, dossier de statut jamais pris pour un livre, progression appData (fichier le plus récent) |
| `sync` · `RestDriveServiceTest` | Chaque appel du tableau Drive API v3 contre un serveur HTTP local (MockWebServer), jeton renouvelé, limites de débit |
| `player` · `PlayerLogicTest` | Identifiants média, chapitres par partie, minuterie, bornes de vitesse |

## Hors périmètre

Envoi de livres depuis le téléphone, fichiers Audible (AAX), iOS ; décompression des zip (affichés avec
un badge « zip »), suggestions, renommage des dossiers et widget (v3).
