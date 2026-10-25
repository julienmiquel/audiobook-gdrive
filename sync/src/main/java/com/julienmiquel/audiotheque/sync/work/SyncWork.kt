package com.julienmiquel.audiotheque.sync.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.settings.AppSettings
import com.julienmiquel.audiotheque.core.sync.SyncRequester
import com.julienmiquel.audiotheque.sync.R
import com.julienmiquel.audiotheque.sync.engine.EngineStatus
import com.julienmiquel.audiotheque.sync.engine.SyncEngine
import com.julienmiquel.audiotheque.sync.engine.SyncOutcome
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Implémenté par l'Application : donne au worker l'accès au moteur. */
interface SyncComponentProvider {
    val syncComponent: SyncComponent
}

class SyncComponent(
    val engine: SyncEngine,
    val scheduler: SyncScheduler,
    val notifications: SyncNotifications,
)

/**
 * Planification : synchro auto toutes les 6 h par défaut (Wi-Fi uniquement, batterie non faible),
 * synchro immédiate au lancement et à chaque changement de statut, nouvel essai espacé quand des
 * déplacements Drive restent en file, reprise au retour du Wi-Fi des téléchargements reportés,
 * nettoyage local à l'échéance des délais. Aucune autre tâche de fond.
 */
class SyncScheduler(
    private val context: Context,
    private val engine: () -> SyncEngine,
) : SyncRequester {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSync(trigger: SyncTrigger) {
        val running = engine()
        if (running.isRunning) {
            running.requestRerun()
            return
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_TRIGGER to trigger.name, KEY_REQUESTED_AT to System.currentTimeMillis()))
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        // À la suite de la synchro en cours plutôt qu'ignorée : un geste fait pendant qu'elle se
        // termine a droit à son déplacement immédiat. Les demandes déjà satisfaites sont sautées.
        workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** (Re)programme la synchro périodique selon les réglages. */
    fun schedulePeriodic(settings: AppSettings) {
        if (!settings.autoSync || !settings.isConnected) {
            workManager.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(settings.requireBatteryNotLow)
            .build()
        val hours = settings.syncIntervalHours.toLong().coerceAtLeast(1)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Après une exécution : nouvel essai des déplacements, reprise au retour du Wi-Fi, nettoyage. */
    fun afterRun(outcome: SyncOutcome) {
        outcome.retryAtMs?.let { retryAt ->
            val delay = (retryAt - System.currentTimeMillis()).coerceAtLeast(SyncEngine.MIN_RETRY_DELAY_MS)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(connected)
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.STATUS_CHANGE.name))
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(WORK_RETRY, ExistingWorkPolicy.REPLACE, request)
        }
        if (outcome.waitingForWifi) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.WIFI.name))
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(WORK_WIFI, ExistingWorkPolicy.KEEP, request)
        }
        scheduleCleanup(outcome.nextCleanupAt)
    }

    /** Nettoyage local (aucun réseau) à l'échéance d'un délai : 7 jours pour Plus tard, 24 h pour Écouté. */
    fun scheduleCleanup(at: Long?) {
        if (at == null) {
            workManager.cancelUniqueWork(WORK_CLEANUP)
            return
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0) + CLEANUP_SLACK_MS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_MODE to MODE_CLEANUP))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(WORK_CLEANUP, ExistingWorkPolicy.REPLACE, request)
    }

    /** Bouton « Annuler » : arrête la synchro en cours sans toucher au planning périodique. */
    fun cancelRunning() {
        engine().cancelCurrent()
        workManager.cancelUniqueWork(WORK_NOW)
    }

    /** Déconnexion : plus aucune tâche de fond. */
    fun cancelAll() {
        engine().cancelCurrent()
        workManager.cancelAllWorkByTag(TAG)
    }

    /** Heure de la prochaine synchro périodique. */
    fun nextPeriodicRun(): Flow<Long?> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_PERIODIC).map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                ?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }
        }

    /** Synchro immédiate en attente (réseau absent…). */
    fun pendingNow(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NOW).map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        }

    companion object {
        const val TAG = "audiotheque-sync"
        const val WORK_NOW = "sync-now"
        const val WORK_PERIODIC = "sync-periodic"
        const val WORK_RETRY = "sync-retry"
        const val WORK_WIFI = "sync-wifi"
        const val WORK_CLEANUP = "cleanup"
        const val KEY_TRIGGER = "trigger"
        const val KEY_REQUESTED_AT = "requestedAt"
        const val KEY_MODE = "mode"
        const val MODE_CLEANUP = "cleanup"

        /** Petite marge après l'échéance, pour ne pas se réveiller une seconde trop tôt. */
        private const val CLEANUP_SLACK_MS = 60_000L
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val component: SyncComponent?
        get() = (applicationContext as? SyncComponentProvider)?.syncComponent

    override suspend fun doWork(): Result {
        val c = component ?: return Result.failure()
        if (inputData.getString(SyncScheduler.KEY_MODE) == SyncScheduler.MODE_CLEANUP) {
            // Sans effet (null) si une synchro tourne : elle nettoie et reprogramme elle-même.
            c.engine.cleanupNow()?.let { c.scheduler.scheduleCleanup(it) }
            return Result.success()
        }
        // Demande déjà satisfaite par une synchro commencée depuis (demandes en file pendant
        // une coupure réseau, par exemple).
        val requestedAt = inputData.getLong(SyncScheduler.KEY_REQUESTED_AT, 0L)
        if (requestedAt in 1 until c.engine.lastRunStartedAt) return Result.success()
        val trigger = inputData.getString(SyncScheduler.KEY_TRIGGER)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() } ?: SyncTrigger.PERIODIC
        var foreground = false
        val outcome = coroutineScope {
            val updater = launch {
                c.engine.status.collect { status -> if (foreground && status.running) c.notifications.updateProgress(status) }
            }
            val result = c.engine.sync(trigger, onDownloadsStart = {
                foreground = runCatching { setForeground(c.notifications.foregroundInfo(c.engine.status.value)) }.isSuccess
            })
            updater.cancel()
            result
        }
        c.scheduler.afterRun(outcome)
        when {
            outcome.needsAuth -> c.notifications.showAuthRequired()
            outcome.result == SyncResult.SUCCESS || outcome.result == SyncResult.PARTIAL -> c.notifications.dismissAuthRequired()
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        component?.notifications?.foregroundInfo(EngineStatus(running = true))
            ?: throw IllegalStateException("Composant de synchro indisponible")
}

/** Action « Annuler » de la notification de synchro. */
class SyncCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? SyncComponentProvider)?.syncComponent?.scheduler?.cancelRunning()
    }
}

class SyncNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.sync_channel_progress), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.sync_channel_progress_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.sync_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.sync_channel_alerts_description) },
        )
    }

    private fun openAppIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, SyncCancelReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(status: EngineStatus): Notification {
        val text = buildString {
            append(status.phase.label)
            if (status.queuedTracks > 0) append(" · ${status.queuedTracks} pistes restantes")
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.sync_cancel), cancelIntent())
            .build()
    }

    fun foregroundInfo(status: EngineStatus): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_PROGRESS, progressNotification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun updateProgress(status: EngineStatus) {
        if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_PROGRESS, progressNotification(status))
    }

    fun showAuthRequired() {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.sync_auth_title))
            .setContentText(context.getString(R.string.sync_auth_text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(NOTIFICATION_AUTH, notification)
    }

    /** Accès Drive rétabli (autorisation renouvelée, synchro réussie, déconnexion) : alerte retirée. */
    fun dismissAuthRequired() {
        manager.cancel(NOTIFICATION_AUTH)
    }

    companion object {
        const val CHANNEL_PROGRESS = "sync_progress"
        const val CHANNEL_ALERTS = "sync_alerts"
        const val NOTIFICATION_PROGRESS = 4201
        const val NOTIFICATION_AUTH = 4202
    }
}
