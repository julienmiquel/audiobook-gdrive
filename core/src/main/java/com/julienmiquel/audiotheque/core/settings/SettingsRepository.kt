package com.julienmiquel.audiotheque.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.AudioPreset
import com.julienmiquel.audiotheque.core.model.BinauralConfig
import com.julienmiquel.audiotheque.core.model.BinauralRoom
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Mode de gestion des statuts de lecture sur un dossier Google Drive. */
enum class StatusSyncMode(val label: String, val subtitle: String) {
    /** Les gestes déplacent réellement le dossier dans Drive (_Nouveaux, _En écoute, _Ecouté). */
    SHARED_DRIVE(
        label = "Déplacer les dossiers Drive",
        subtitle = "Un changement de statut déplace physiquement le dossier sur Drive pour tout le monde.",
    ),

    /** Chaque auditeur garde son propre statut dans son fichier de progression sans toucher aux dossiers des autres. */
    PERSONAL_FAMILY(
        label = "Mode familial (statut par auditeur)",
        subtitle = "Chacun gère ses livres « En écoute », « Plus tard » et « Écoutés » sans déplacer les dossiers Drive des autres.",
    ),
    ;

    companion object {
        fun fromName(name: String?): StatusSyncMode = entries.firstOrNull { it.name == name } ?: SHARED_DRIVE
    }
}

/** Profil d'auditeur configuré sur cet appareil (ex. Julien, Julie, Clément). */
@Serializable
data class ListenerProfile(
    val key: String,
    val displayName: String,
)

/** Ordre de tri choisi par l'utilisateur dans la bibliothèque. */
enum class LibrarySort(val label: String) {
    DEFAULT("Tri intelligent (par défaut)"),
    RECENTLY_LISTENED("Écoutés récemment"),
    ADDED_DESC("Ajoutés récemment"),
    TITLE_ASC("Titre (A → Z et tomes)"),
    AUTHOR_ASC("Auteur (A → Z)"),
    DURATION_ASC("Durée (du plus court)"),
    DURATION_DESC("Durée (du plus long)"),
    ;

    companion object {
        fun fromName(name: String?): LibrarySort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Réglages de l'app (écrans Réglages et Dossiers synchronisés). */
data class AppSettings(
    val accountEmail: String? = null,
    val accountName: String? = null,
    val onboardingDone: Boolean = false,
    /** Synchro auto activée par défaut toutes les 6 h, en Wi-Fi uniquement, batterie non faible. */
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val syncIntervalHours: Int = 6,
    val requireBatteryNotLow: Boolean = true,
    /** Délais de nettoyage : pistes de _A écouter plus tard gardées 7 jours, de _Ecouté 24 h. */
    val laterRetentionDays: Int = 7,
    val doneRetentionHours: Int = 24,
    /** Pas du saut : 10, 15 ou 30 s. */
    val skipSeconds: Int = 30,
    val theme: ThemeMode = ThemeMode.DARK,
    /** Aperçu des _Nouveaux : télécharger la première piste. */
    val previewNew: Boolean = false,
    /** Onglet Archives (_archives) affiché ; masquable dans Réglages. */
    val showArchives: Boolean = true,
    val showHidden: Boolean = false,
    /** Enregistrer dans [Stockage interne]/Audiobooks (si l'accès à tous les fichiers est accordé). */
    val useSharedStorage: Boolean = true,
    /** Enrichissement Google Books automatique après confirmation du titre et de l'auteur. */
    val googleBooksEnrichment: Boolean = true,
    val lastPlayedBookId: String? = null,
    /** Préréglage audio par défaut du lecteur (normalisation, voix, niveau très bas, binaural). */
    val audioPreset: AudioPreset = AudioPreset.NONE,
    /** Suppression automatique des silences entre les phrases. */
    val skipSilence: Boolean = false,
    /** Recul adaptatif à la reprise selon la durée de la pause (2 s à 30 s). */
    val smartRewind: Boolean = true,
    /** Secouer le téléphone pendant la minuterie de sommeil pour prolonger de 10 min. */
    val shakeToExtendTimer: Boolean = true,
    /** Dernière durée libre choisie pour la minuterie de sommeil (minutes). */
    val customTimerMinutes: Int = 20,
    /** Azimut de la voix en mode binaural (-30 à +30°). */
    val binauralAzimuthDeg: Int = 0,
    /** Pièce simulée en mode binaural. */
    val binauralRoom: BinauralRoom = BinauralRoom.SMALL,
    /** Tri actif de la bibliothèque. */
    val librarySort: LibrarySort = LibrarySort.DEFAULT,
    /** Affichage de la bibliothèque en grille de pochettes (sinon liste détaillée). */
    val libraryGrid: Boolean = false,
    /** Profils d'auditeurs supplémentaires configurés sur cet appareil (ex. Julie, Clément). */
    val profiles: List<ListenerProfile> = emptyList(),
    /** Clé du profil actif (null = compte Google principal connecté). */
    val activeProfileKey: String? = null,
    /** Mode de gestion des statuts : déplacement des dossiers Drive ou statut personnel par auditeur. */
    val statusSyncMode: StatusSyncMode = StatusSyncMode.SHARED_DRIVE,
) {
    /** Clé d'auditeur de la progression et des signets (propre à chaque utilisateur/profil). */
    val accountKey: String
        get() = activeProfileKey?.takeIf { it.isNotBlank() }
            ?: accountEmail?.takeIf { it.isNotBlank() }
            ?: Accounts.LOCAL

    /** Nom affiché de l'auditeur actif. */
    val activeListenerName: String
        get() {
            val active = activeProfileKey?.takeIf { it.isNotBlank() }
            if (active != null) {
                profiles.firstOrNull { it.key == active }?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
                if (accountEmail != null && active.equals(accountEmail, ignoreCase = true)) {
                    return accountName?.takeIf { it.isNotBlank() } ?: Accounts.displayNameForKey(accountEmail)
                }
                return Accounts.displayNameForKey(active)
            }
            return accountName?.takeIf { it.isNotBlank() }
                ?: accountEmail?.let(Accounts::displayNameForKey)
                ?: "Local"
        }

    /** Vrai si le mode multi-profils ou familial est utilisé. */
    val hasMultipleListeners: Boolean
        get() = profiles.isNotEmpty() || activeProfileKey != null || statusSyncMode == StatusSyncMode.PERSONAL_FAMILY

    val isConnected: Boolean get() = !accountEmail.isNullOrBlank()

    val binauralConfig: BinauralConfig get() = BinauralConfig(binauralAzimuthDeg, binauralRoom)

    companion object {
        val SKIP_CHOICES = listOf(10, 15, 30)
        val INTERVAL_CHOICES = listOf(1, 3, 6, 12, 24)
        val LATER_RETENTION_CHOICES = listOf(1, 3, 7, 14, 30)
        val DONE_RETENTION_CHOICES = listOf(0, 1, 24, 72, 168)
    }
}

/** État persistant du moteur de synchro. */
data class SyncState(
    /** Jeton du flux de modifications Drive (changes.getStartPageToken / changes.list). */
    val pageToken: String? = null,
    val pageTokenAccount: String? = null,
    val lastFullInventoryAt: Long? = null,
    val driveQuotaUsed: Long? = null,
    val driveQuotaLimit: Long? = null,
    val quotaUpdatedAt: Long? = null,
    /** Autorisation Google Drive refusée ou expirée : à renouveler depuis l'app. */
    val authRequired: Boolean = false,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .distinctUntilChanged()

    val syncState: Flow<SyncState> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSyncState() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun currentSyncState(): SyncState = syncState.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    suspend fun updateSyncState(transform: (SyncState) -> SyncState) {
        dataStore.edit { prefs -> transform(prefs.toSyncState()).writeTo(prefs) }
    }

    private object K {
        val accountEmail = stringPreferencesKey("account_email")
        val accountName = stringPreferencesKey("account_name")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val autoSync = booleanPreferencesKey("auto_sync")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val syncIntervalHours = intPreferencesKey("sync_interval_hours")
        val requireBatteryNotLow = booleanPreferencesKey("battery_not_low")
        val laterRetentionDays = intPreferencesKey("later_retention_days")
        val doneRetentionHours = intPreferencesKey("done_retention_hours")
        val skipSeconds = intPreferencesKey("skip_seconds")
        val theme = stringPreferencesKey("theme")
        val previewNew = booleanPreferencesKey("preview_new")
        val showArchives = booleanPreferencesKey("show_archives_tab")
        val showHidden = booleanPreferencesKey("show_hidden")
        val useSharedStorage = booleanPreferencesKey("use_shared_storage")
        val googleBooks = booleanPreferencesKey("google_books")
        val lastPlayedBookId = stringPreferencesKey("last_played_book")
        val audioPreset = stringPreferencesKey("audio_preset")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val smartRewind = booleanPreferencesKey("smart_rewind")
        val shakeToExtendTimer = booleanPreferencesKey("shake_extend_timer")
        val customTimerMinutes = intPreferencesKey("custom_timer_minutes")
        val binauralAzimuthDeg = intPreferencesKey("binaural_azimuth_deg")
        val binauralRoom = stringPreferencesKey("binaural_room")
        val librarySort = stringPreferencesKey("library_sort")
        val libraryGrid = booleanPreferencesKey("library_grid")
        val profilesJson = stringPreferencesKey("listener_profiles_json")
        val activeProfileKey = stringPreferencesKey("active_profile_key")
        val statusSyncMode = stringPreferencesKey("status_sync_mode")

        val pageToken = stringPreferencesKey("sync_page_token")
        val pageTokenAccount = stringPreferencesKey("sync_page_token_account")
        val lastFullInventoryAt = longPreferencesKey("sync_last_full_inventory")
        val quotaUsed = longPreferencesKey("drive_quota_used")
        val quotaLimit = longPreferencesKey("drive_quota_limit")
        val quotaUpdatedAt = longPreferencesKey("drive_quota_updated")
        val authRequired = booleanPreferencesKey("drive_auth_required")
    }

    private val profilesCodec = Json { ignoreUnknownKeys = true }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val parsedProfiles = this[K.profilesJson]?.let { raw ->
            runCatching { profilesCodec.decodeFromString<List<ListenerProfile>>(raw) }.getOrNull()
        }.orEmpty()
        return AppSettings(
            accountEmail = this[K.accountEmail],
            accountName = this[K.accountName],
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            autoSync = this[K.autoSync] ?: d.autoSync,
            wifiOnly = this[K.wifiOnly] ?: d.wifiOnly,
            syncIntervalHours = this[K.syncIntervalHours] ?: d.syncIntervalHours,
            requireBatteryNotLow = this[K.requireBatteryNotLow] ?: d.requireBatteryNotLow,
            laterRetentionDays = this[K.laterRetentionDays] ?: d.laterRetentionDays,
            doneRetentionHours = this[K.doneRetentionHours] ?: d.doneRetentionHours,
            skipSeconds = this[K.skipSeconds] ?: d.skipSeconds,
            theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            previewNew = this[K.previewNew] ?: d.previewNew,
            showArchives = this[K.showArchives] ?: d.showArchives,
            showHidden = this[K.showHidden] ?: d.showHidden,
            useSharedStorage = this[K.useSharedStorage] ?: d.useSharedStorage,
            googleBooksEnrichment = this[K.googleBooks] ?: d.googleBooksEnrichment,
            lastPlayedBookId = this[K.lastPlayedBookId],
            audioPreset = AudioPreset.fromName(this[K.audioPreset]),
            skipSilence = this[K.skipSilence] ?: d.skipSilence,
            smartRewind = this[K.smartRewind] ?: d.smartRewind,
            shakeToExtendTimer = this[K.shakeToExtendTimer] ?: d.shakeToExtendTimer,
            customTimerMinutes = (this[K.customTimerMinutes] ?: d.customTimerMinutes).coerceIn(5, 180),
            binauralAzimuthDeg = (this[K.binauralAzimuthDeg] ?: d.binauralAzimuthDeg).coerceIn(-45, 45),
            binauralRoom = BinauralRoom.fromName(this[K.binauralRoom]),
            librarySort = LibrarySort.fromName(this[K.librarySort]),
            libraryGrid = this[K.libraryGrid] ?: d.libraryGrid,
            profiles = parsedProfiles,
            activeProfileKey = this[K.activeProfileKey],
            statusSyncMode = StatusSyncMode.fromName(this[K.statusSyncMode]),
        )
    }

    private fun AppSettings.writeTo(p: MutablePreferences) {
        p.putOrRemove(K.accountEmail, accountEmail)
        p.putOrRemove(K.accountName, accountName)
        p[K.onboardingDone] = onboardingDone
        p[K.autoSync] = autoSync
        p[K.wifiOnly] = wifiOnly
        p[K.syncIntervalHours] = syncIntervalHours
        p[K.requireBatteryNotLow] = requireBatteryNotLow
        p[K.laterRetentionDays] = laterRetentionDays
        p[K.doneRetentionHours] = doneRetentionHours
        p[K.skipSeconds] = skipSeconds
        p[K.theme] = theme.name
        p[K.previewNew] = previewNew
        p[K.showArchives] = showArchives
        p[K.showHidden] = showHidden
        p[K.useSharedStorage] = useSharedStorage
        p[K.googleBooks] = googleBooksEnrichment
        p.putOrRemove(K.lastPlayedBookId, lastPlayedBookId)
        p[K.audioPreset] = audioPreset.name
        p[K.skipSilence] = skipSilence
        p[K.smartRewind] = smartRewind
        p[K.shakeToExtendTimer] = shakeToExtendTimer
        p[K.customTimerMinutes] = customTimerMinutes
        p[K.binauralAzimuthDeg] = binauralAzimuthDeg
        p[K.binauralRoom] = binauralRoom.name
        p[K.librarySort] = librarySort.name
        p[K.libraryGrid] = libraryGrid
        p.putOrRemove(K.profilesJson, profiles.takeIf { it.isNotEmpty() }?.let { profilesCodec.encodeToString(it) })
        p.putOrRemove(K.activeProfileKey, activeProfileKey)
        p[K.statusSyncMode] = statusSyncMode.name
    }

    private fun Preferences.toSyncState() = SyncState(
        pageToken = this[K.pageToken],
        pageTokenAccount = this[K.pageTokenAccount],
        lastFullInventoryAt = this[K.lastFullInventoryAt],
        driveQuotaUsed = this[K.quotaUsed],
        driveQuotaLimit = this[K.quotaLimit],
        quotaUpdatedAt = this[K.quotaUpdatedAt],
        authRequired = this[K.authRequired] ?: false,
    )

    private fun SyncState.writeTo(p: MutablePreferences) {
        p.putOrRemove(K.pageToken, pageToken)
        p.putOrRemove(K.pageTokenAccount, pageTokenAccount)
        p.putOrRemove(K.lastFullInventoryAt, lastFullInventoryAt)
        p.putOrRemove(K.quotaUsed, driveQuotaUsed)
        p.putOrRemove(K.quotaLimit, driveQuotaLimit)
        p.putOrRemove(K.quotaUpdatedAt, quotaUpdatedAt)
        p[K.authRequired] = authRequired
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        fun create(context: Context): SettingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                produceFile = { context.applicationContext.preferencesDataStoreFile("settings") },
            ),
        )

        /** Pour les tests : magasin dans un fichier donné. */
        fun forFile(file: File): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
