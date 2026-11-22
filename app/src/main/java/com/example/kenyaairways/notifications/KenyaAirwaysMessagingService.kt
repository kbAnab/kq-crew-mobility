package com.example.kenyaairways.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class KenyaAirwaysMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        PushNotifications.registerInstallation(this, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.data["title"] ?: message.notification?.title ?: getString(com.example.kenyaairways.R.string.app_name)
        val body = message.data["body"] ?: message.notification?.body ?: return
        val id = message.data["notification_id"] ?: message.messageId ?: "$title:$body"
        PushNotifications.show(this, title, body, id)
    }
}
