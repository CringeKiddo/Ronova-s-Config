package com.ronova.config.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.ronova.config.R
import com.ronova.config.adb.AdbProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Builds the "type the 6-digit code right here" notification so the user can keep the
 * Wireless debugging pairing dialog open (split-screen) and reply from the notification shade.
 */
object PairingNotifier {
    const val CHANNEL_ID = "ronova_pairing"
    const val NOTIFICATION_ID = 4402
    const val ACTION_PAIR_REPLY = "com.ronova.config.action.PAIR_REPLY"
    const val KEY_CODE = "ronova_pairing_code"

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADB pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Inline reply for the wireless debugging pairing code"
            }
        )
        return nm
    }

    fun show(context: Context, headline: String = "Enter the pairing code") {
        val app = context.applicationContext
        val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val replyIntent = PendingIntent.getBroadcast(
            app, 0,
            Intent(app, PairingReplyReceiver::class.java).setAction(ACTION_PAIR_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(R.drawable.ic_stat_ronova, "Enter code", replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ronova)
            .setContentTitle(headline)
            .setContentText("Settings → Developer options → Wireless debugging → Pair device with pairing code, then type the code here.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Settings → Developer options → Wireless debugging → Pair device with pairing code, then type the 6-digit code here."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
        manager(app).notify(NOTIFICATION_ID, notification)
    }

    fun showProgress(context: Context) {
        val app = context.applicationContext
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ronova)
            .setContentTitle("Pairing...")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager(app).notify(NOTIFICATION_ID, notification)
    }

    fun showResult(context: Context, success: Boolean, message: String) {
        val app = context.applicationContext
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ronova)
            .setContentTitle(if (success) "Paired" else "Pairing failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        manager(app).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        manager(context.applicationContext).cancel(NOTIFICATION_ID)
    }
}

class PairingReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotifier.ACTION_PAIR_REPLY) return
        val app = context.applicationContext
        val raw = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotifier.KEY_CODE)?.toString().orEmpty()
        val code = raw.filter { it.isDigit() }
        if (code.length != 6) {
            PairingNotifier.show(app, "That was not a 6-digit code - try again")
            return
        }

        val pending = goAsync()
        PairingNotifier.showProgress(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val adb = AdbProtocol.get(app)
                val pair = adb.pairWithDiscovered(code)
                if (pair.isFailure) {
                    PairingNotifier.showResult(app, false, pair.exceptionOrNull()?.message ?: "Unknown error")
                    return@launch
                }
                val connect = adb.connectAuto()
                if (connect.isSuccess) {
                    PairingNotifier.showResult(app, true, "Paired and connected. You can enable the engine now.")
                } else {
                    PairingNotifier.showResult(
                        app, true,
                        "Paired, but connecting failed: ${connect.exceptionOrNull()?.message}. Toggle Wireless debugging off and on, then enable the engine."
                    )
                }
            } catch (e: Exception) {
                PairingNotifier.showResult(app, false, e.message ?: e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }
}
