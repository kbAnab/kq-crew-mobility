package com.example.kenyaairways.crewcontroller

import android.widget.Toast
import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi

class AlertsFragment : ListFragment("Alerts") {

    private lateinit var adapter: RowAdapter<AlertItem>
    private var filter = 0
    private var alerts: List<AlertItem> = emptyList()
    private var loadError: String? = null

    private fun color(s: Severity) = when (s) {
        Severity.HIGH -> R.color.primary
        Severity.MEDIUM -> R.color.duty_reserve
        Severity.LOW -> R.color.duty_flight
        Severity.UNKNOWN -> R.color.text_secondary
    }

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        adapter = RowAdapter(emptyList()) { row, a ->
            row.setTexts(a.title, a.detail, a.time)
            if (a.acknowledged) {
                row.badge("Acknowledged", R.color.text_secondary)
                row.accent(R.color.divider)
                row.root.alpha = 0.6f
            } else {
                row.badge(a.severity.label, color(a.severity))
                row.accent(color(a.severity))
            }
            row.root.setOnClickListener {
                if (!a.acknowledged) {
                    RecommendationApi.acknowledgeAlert(a.id) { result ->
                        if (!isViewReady) return@acknowledgeAlert
                        result.onSuccess { refresh() }.onFailure {
                            Toast.makeText(requireContext(), "Could not acknowledge alert: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        b.rvList.adapter = adapter
        setupChips(listOf("All") + Severity.entries.map { it.label }) { filter = it; refresh() }
    }

    private fun refresh() {
        RecommendationApi.loadAlerts { result ->
            if (!isViewReady) return@loadAlerts
            result.onSuccess { alerts = it; loadError = null }.onFailure {
                alerts = emptyList()
                loadError = "Could not load live alerts: ${it.message ?: "service unavailable"}"
            }
            render()
        }
    }

    private fun render() {
        val all = alerts
        val list = if (filter == 0) all else all.filter { it.severity == Severity.entries[filter - 1] }
        adapter.submit(list)
        showEmpty(list.isEmpty(), loadError ?: "No alerts")
        setSummary("${all.count { !it.acknowledged }} unacknowledged · live service data")
    }
}
