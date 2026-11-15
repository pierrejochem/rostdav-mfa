package app.rustmfa.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.rustmfa.android.MainActivity
import app.rustmfa.android.R

/** Posts the sign-in request notifications. Text: service and login device, never a number. */
class AndroidNotificationSink(private val context: Context) : NotificationSink {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun show(spec: NotificationSpec) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_CHALLENGE, spec.challengeId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, spec.challengeId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(context.getString(R.string.notif_text, spec.serviceName, spec.loginDevice))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        nm.notify(spec.challengeId.hashCode(), n)
    }

    override fun cancel(challengeId: String) = nm.cancel(challengeId.hashCode())

    companion object {
        const val CHANNEL = "approvals"
        const val EXTRA_CHALLENGE = "challenge_id"
    }
}
