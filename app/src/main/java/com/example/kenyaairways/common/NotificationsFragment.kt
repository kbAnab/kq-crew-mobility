package com.example.kenyaairways.common

import android.graphics.Typeface
import android.widget.Toast
import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi

class NotificationsFragment : ListFragment("Notifications") {

    private lateinit var adapter: RowAdapter<AppNotification>
    private var notifications: List<AppNotification> = emptyList()

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        adapter = RowAdapter(emptyList()) { row, n ->
            row.setTexts(n.title, n.message, n.time)
            if (!n.read) {
                row.accent(R.color.primary)
            } else {
                row.tvTitle.setTypeface(null, Typeface.NORMAL)
                row.root.alpha = 0.7f
            }
            row.root.setOnClickListener {
                if (!n.read) RecommendationApi.markNotificationRead(n.id) { result ->
                    if (!isViewReady) return@markNotificationRead
                    result.onSuccess { refresh() }.onFailure {
                        Toast.makeText(requireContext(), "Could not update notification: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        b.rvList.adapter = adapter
        refresh()
    }

    private fun refresh() {
        setSummary("Loading notifications…")
        RecommendationApi.loadNotifications { result ->
            if (!isViewReady) return@loadNotifications
            result.onSuccess { notifications = it; render() }.onFailure {
                notifications = emptyList()
                adapter.submit(emptyList())
                showEmpty(true, "Could not load notifications: ${it.message ?: "service unavailable"}")
                setSummary("Notifications unavailable")
            }
        }
    }

    private fun render() {
        adapter.submit(notifications)
        showEmpty(notifications.isEmpty(), "No notifications")
        val unread = notifications.count { !it.read }
        if (unread == 0) {
            setSummary("All caught up")
        } else {
            setSummary("$unread unread · tap here to mark all as read") {
                val pendingReads = notifications.filter { !it.read }
                var remaining = pendingReads.size
                var failed = false
                pendingReads.forEach { notification ->
                    RecommendationApi.markNotificationRead(notification.id) { result ->
                        if (!isViewReady) return@markNotificationRead
                        failed = failed || result.isFailure
                        remaining -= 1
                        if (remaining == 0) {
                            if (failed) Toast.makeText(requireContext(), "Some notifications could not be updated.", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }
                }
            }
        }
    }
}
