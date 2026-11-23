package com.example.kenyaairways.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.kenyaairways.R
import com.example.kenyaairways.data.RecommendationApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging

object PushNotifications {
    private const val PREFS = "settings"
    private const val PUSH_ENABLED = "push"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PUSH_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PUSH_ENABLED, enabled).apply()
        if (enabled) syncToken(context) else unregisterCurrentToken()
    }

    fun syncToken(context: Context) {
        if (!isEnabled(context) || FirebaseAuth.getInstance().currentUser == null || !canPostNotifications(context)) return
        createChannel(context)
        FirebaseMessaging.getInstance().register()
            .addOnSuccessListener {
                FirebaseInstallations.getInstance().id.addOnSuccessListener { installationId ->
                    registerInstallation(context, installationId)
                }
            }
    }

    fun registerInstallation(context: Context, installationId: String) {
        if (installationId.isBlank() || !isEnabled(context) || FirebaseAuth.getInstance().currentUser == null) return
        RecommendationApi.baseUrl = context.getString(R.string.recommendation_base_url).trimEnd('/')
        RecommendationApi.registerInstallationId(installationId)
    }

    fun unregisterCurrentToken(onComplete: () -> Unit = {}) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            onComplete()
            return
        }
        FirebaseInstallations.getInstance().id
            .addOnCompleteListener { task ->
                val installationId = task.result
                if (!task.isSuccessful || installationId.isNullOrBlank()) {
                    onComplete()
                    return@addOnCompleteListener
                }
                RecommendationApi.unregisterInstallationId(installationId) { onComplete() }
            }
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, title: String, body: String, notificationId: String) {
        if (!isEnabled(context) || !canPostNotifications(context)) return
        createChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            it.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            android.app.PendingIntent.getActivity(
                context,
                notificationId.hashCode(),
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            context.getString(R.string.notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_flight)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(notificationId.hashCode(), notification)
            }

        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    context.getString(R.string.notification_channel_id),
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}
