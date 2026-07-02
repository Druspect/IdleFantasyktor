package com.fantasyidler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.fantasyidler.notification.SessionNotificationManager
import com.fantasyidler.automation.AutomationControlRepository
import com.fantasyidler.repository.QueuedSessionStarter
import com.fantasyidler.repository.SessionRepository
import com.fantasyidler.repository.WorkerQueuedSessionStarter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SessionAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var notificationManager: SessionNotificationManager
    @Inject lateinit var queuedSessionStarter: QueuedSessionStarter
    @Inject lateinit var workerQueuedSessionStarter: WorkerQueuedSessionStarter
    @Inject lateinit var automationControl: AutomationControlRepository

    override fun onReceive(context: Context, intent: Intent) {
        // Acquire a partial wake lock so the CPU stays awake after onReceive() returns.
        // Without this, setAndAllowWhileIdle only holds the wake lock until onReceive()
        // returns, and the coroutine below can be suspended mid-execution on some devices.
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fantasyidler:session_alarm")
            .apply { acquire(30_000L) }

        val pending          = goAsync()
        val sessionId        = intent.getStringExtra(KEY_SESSION_ID) ?: run { pending.finish(); wakeLock.release(); return }
        val skillDisplayName = intent.getStringExtra(KEY_SKILL_DISPLAY_NAME) ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = sessionRepository.getSession(sessionId)
                sessionRepository.markCompleted(sessionId)
                // Compute how late the alarm fired so the next session can be backdated.
                val now = System.currentTimeMillis()
                val backdateMs = if (session != null) maxOf(0L, now - session.endsAt) else 0L
                if (session?.isWorkerSession == true) {
                    val slot = session.workerSlot.coerceAtLeast(1)
                    val workerStarted = workerQueuedSessionStarter.startNextQueued(slot)
                    if (!workerStarted) notificationManager.showSessionComplete(skillDisplayName)
                } else if (automationControl.allowsNativeQueueAutoAdvance()) {
                    var catchUpMs = backdateMs
                    while (catchUpMs > 0) {
                        val used = try { queuedSessionStarter.insertNextQueuedAsOffline(catchUpMs) } catch (_: Exception) { 0L }
                        if (used == 0L) break
                        catchUpMs -= used
                    }
                    val started = queuedSessionStarter.startNextQueued(backdateMs = catchUpMs)
                    if (!started) notificationManager.showSessionComplete(skillDisplayName)
                } else {
                    notificationManager.showSessionComplete(skillDisplayName)
                }
            } finally {
                pending.finish()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    companion object {
        const val KEY_SESSION_ID         = "session_id"
        const val KEY_SKILL_DISPLAY_NAME = "skill_display_name"
    }
}
