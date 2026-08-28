package com.aura.music.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aura.music.AuraApplication

/**
 * BroadcastReceiver réveillé par AlarmManager pour garantir l'arrêt de la musique
 * à l'heure exacte du minuteur de veille, même sous Doze Mode ou si l'UI a été fermée.
 */
class SleepTimerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SLEEP_TIMER_EXPIRED = "com.aura.music.action.SLEEP_TIMER_EXPIRED"
        private const val TAG = "SleepTimerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SLEEP_TIMER_EXPIRED) {
            Log.i(TAG, "Sleep timer alarm fired via AlarmManager broadcast")
            try {
                val app = context.applicationContext as? AuraApplication
                val orchestrator = app?.container?.playbackOrchestrator
                orchestrator?.onSleepTimerAlarmFired()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle sleep timer alarm expiration", e)
            }
        }
    }
}
