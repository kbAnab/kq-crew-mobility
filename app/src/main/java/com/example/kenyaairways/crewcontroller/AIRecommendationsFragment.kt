package com.example.kenyaairways.crewcontroller

import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi
import com.example.kenyaairways.databinding.ItemRowBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AIRecommendationsFragment : ListFragment("AI Recommendations") {

    companion object {
        private const val ARG_FLIGHT = "flight"
        fun newInstance(flightId: String) = AIRecommendationsFragment().apply {
            arguments = bundleOf(ARG_FLIGHT to flightId)
        }
    }


    private lateinit var adapter: RowAdapter<Recommendation>
    private var flight: OpenFlight? = null
    private var flights: List<OpenFlight> = emptyList()
    private val submittedCrewIds = mutableSetOf<String>()

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        adapter = RowAdapter(emptyList()) { row, r -> bindRecommendation(row, r) }
        b.rvList.adapter = adapter
        loadFlights()
    }

    private var requestId = 0

    private fun load() {
        val f = flight
        if (f == null) {
            adapter.submit(emptyList()); setSummary(null)
            showEmpty(true, "No flights need crew")
            return
        }
        val needed = (f.slots - f.filled).coerceAtLeast(1)
        val title = "${f.flightNo} · ${f.route} · needs $needed ${f.qualification}"
        adapter.submit(emptyList())
        showEmpty(true, "Loading recommendations…")
        setSummary(title)

        val thisRequest = ++requestId
        RecommendationApi.recommend(f.id, needed) { result ->
            // ignore late answers if the screen was closed or another flight was chosen
            if (!isViewReady || thisRequest != requestId) return@recommend
            result.onSuccess { recs ->
                adapter.submit(recs)
                showEmpty(recs.isEmpty(), "No crew meet the duty and rest rules for this flight")
                setSummary("$title · model results · hard eligibility checks applied; approval is still required") { pickFlight() }
            }.onFailure { error ->
                adapter.submit(emptyList())
                showEmpty(true, "Live recommendations are unavailable: ${error.message ?: "service error"}")
                setSummary("No model result was returned: ${error.message ?: "service error"}") { pickFlight() }
            }
        }
    }

    private fun loadFlights() {
        showEmpty(true, "Loading live flights…")
        RecommendationApi.loadFlights { result ->
            if (!isViewReady) return@loadFlights
            result.onSuccess { all ->
                flights = all.filter { it.slots > it.filled }
                val requestedId = arguments?.getString(ARG_FLIGHT)
                flight = flights.firstOrNull { it.id == requestedId } ?: flights.firstOrNull()
                if (flight == null) {
                    adapter.submit(emptyList())
                    showEmpty(true, "No open flights are available.")
                    setSummary("No live flight positions need crew.")
                } else load()
            }.onFailure {
                adapter.submit(emptyList())
                showEmpty(true, "Could not load the live flight schedule: ${it.message ?: "service error"}")
                setSummary("Flight data unavailable")
            }
        }
    }

    private fun pickFlight() {
        val open = flights
        if (open.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Choose flight")
            .setItems(open.map { "${it.flightNo} ${it.route} (${it.qualification})" }.toTypedArray()) { _, i ->
                flight = open[i]; load()
            }.show()
    }

    private fun bindRecommendation(row: ItemRowBinding, r: Recommendation) {
        val c = r.candidate
        val f = flight ?: return
        val violation = when {
            !c.qualification.equals(f.qualification, ignoreCase = true) -> "Qualification does not match this position"
            !c.dutyHours.isFinite() || c.dutyHours < 0 -> "Invalid duty-hour value returned"
            !c.restHours.isFinite() || c.restHours < 0 -> "Invalid rest-period value returned"
            else -> null
        }
        val eligible = violation == null
        val color = if (eligible && r.recommended) R.color.duty_off else R.color.duty_flight
        row.setTexts("${r.rank}. ${c.name}",
            "${c.qualification} · ${c.experience} yrs · rest ${c.restHours} h · duty ${c.dutyHours} h",
            "Suitability score: ${String.format(java.util.Locale.getDefault(), "%.1f%%", c.score)}" +
                    (violation?.let { " · Blocked: $it" } ?: r.reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()))
        row.badge(when {
            !eligible -> "Not eligible"
            r.recommended -> "Model recommended · eligible"
            else -> "Eligible alternate"
        }, color)
        row.accent(color)
        row.actions.visibility = View.VISIBLE
        val alreadySubmitted = c.crewId in submittedCrewIds
        row.btnPrimary.text = if (alreadySubmitted) "Request submitted" else "Request approval"
        row.btnPrimary.isEnabled = eligible && !alreadySubmitted
        row.btnPrimary.setOnClickListener {
            row.btnPrimary.isEnabled = false
            RecommendationApi.requestAssignment(f.id, c.crewId, "AI recommender") { result ->
                if (!isViewReady) return@requestAssignment
                result.onSuccess {
                    submittedCrewIds.add(c.crewId)
                    adapter.notifyDataSetChanged()
                    Toast.makeText(requireContext(), "${c.name} was sent for scheduler approval.", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    row.btnPrimary.isEnabled = eligible
                    Toast.makeText(requireContext(), "Could not submit assignment: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
