package com.example.kenyaairways.crewcontroller

import android.view.View
import com.example.kenyaairways.data.RecommendationApi
import com.example.kenyaairways.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CrewAllocationFragment : ListFragment("Crew Allocation") {

    private lateinit var adapter: RowAdapter<OpenFlight>
    private var filter = 0
    private var flights: List<OpenFlight> = emptyList()
    private var loadError: String? = null

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        adapter = RowAdapter(emptyList()) { row, f -> bindFlight(row, f) }
        b.rvList.adapter = adapter
        setupChips(listOf("All", "Open", "Filled")) { filter = it; refresh() }
        loadFlights()
    }

    private fun refresh() {
        val all = flights
        val list = when (filter) {
            1 -> all.filter { it.filled < it.slots }
            2 -> all.filter { it.filled >= it.slots }
            else -> all
        }
        adapter.submit(list)
        showEmpty(list.isEmpty(), loadError ?: if (all.isEmpty()) "No live flight records were returned." else "No flights in this view")
        setSummary(loadError ?: "${all.count { it.filled < it.slots }} of ${all.size} live flights have open positions")
    }

    private fun loadFlights() {
        setSummary("Loading live flights…")
        RecommendationApi.loadFlights { result ->
            if (!isViewReady) return@loadFlights
            result.onSuccess {
                flights = it
                loadError = null
            }.onFailure {
                flights = emptyList()
                loadError = "Live flight service unavailable: ${it.message ?: "request failed"}"
            }
            refresh()
        }
    }

    private fun bindFlight(row: com.example.kenyaairways.databinding.ItemRowBinding, f: OpenFlight) {
        val open = f.slots - f.filled
        val color = if (open > 0) R.color.duty_reserve else R.color.duty_off
        row.setTexts("${f.flightNo}  ${f.route}", "${f.qualification} · departs ${formatDeparture(f.departure)}",
            "${f.filled} of ${f.slots} filled")
        row.badge(if (open > 0) "$open open" else "Filled", color)
        row.accent(color)
        if (f.slots > 0) row.showProgress(f.filled * 100 / f.slots, color)
        if (open > 0) {
            row.actions.visibility = View.VISIBLE
            row.btnPrimary.text = "Find crew"
            row.btnPrimary.setOnClickListener {
                (requireActivity() as MainActivity)
                    .open(AIRecommendationsFragment.newInstance(f.id), "AI Recommendations")
            }
        }
    }

    private fun formatDeparture(value: String): String = runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Africa/Nairobi"))
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm z", Locale.getDefault()))
    }.getOrDefault(value.ifBlank { "time unavailable" })
}
