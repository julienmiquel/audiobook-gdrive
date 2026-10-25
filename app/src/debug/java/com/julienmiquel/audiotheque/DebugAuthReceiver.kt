package com.julienmiquel.audiotheque

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.julienmiquel.audiotheque.core.model.SyncTrigger

/**
 * Builds debug uniquement (absent de l'APK release) : simule, en mode démo, une autorisation
 * Google Drive expirée pour vérifier le bandeau « Renouveler » et la notification.
 *
 *     adb shell am broadcast -n com.julienmiquel.audiotheque/.DebugAuthReceiver
 *     adb shell am broadcast -n com.julienmiquel.audiotheque/.DebugAuthReceiver --ez expired false
 */
class DebugAuthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? AudiothequeApp)?.container ?: return
        container.demoDrive.authExpired = intent.getBooleanExtra(EXTRA_EXPIRED, true)
        container.requestSync(SyncTrigger.MANUAL)
    }

    private companion object {
        const val EXTRA_EXPIRED = "expired"
    }
}
