package com.example.kenyaairways.manager

import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi
import com.example.kenyaairways.data.UserRepository
import com.google.firebase.auth.FirebaseAuth

/** Live operational counts; percentages are omitted until a trusted reporting source exists. */
class PerformanceFragment : ListFragment("Performance") {

    private lateinit var adapter: RowAdapter<Pair<String, String>>

    override fun onReady() {
        adapter = RowAdapter(emptyList()) { row, item ->
            row.setTexts(item.first, item.second)
            row.accent(R.color.duty_flight)
        }
        b.rvList.adapter = adapter
        setSummary("Loading live operational summaries…")
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            showEmpty(true, "Sign in to load reports")
            return
        }
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        UserRepository.loadDashboardStats(Role.MANAGER, uid) { userResult ->
            if (!isViewReady) return@loadDashboardStats
            val crewStats = userResult.getOrElse {
                listOf("Crew and availability" to "Unavailable: ${it.message ?: "Firestore error"}")
            }
            RecommendationApi.loadFlights { flightResult ->
                if (!isViewReady) return@loadFlights
                val flights = flightResult.getOrElse { emptyList() }
                val flightStats = if (flightResult.isSuccess) listOf(
                    "Flights in schedule" to flights.size.toString(),
                    "Open crew positions" to flights.sumOf { (it.slots - it.filled).coerceAtLeast(0) }.toString()
                ) else listOf("Flight schedule" to "Unavailable: ${flightResult.exceptionOrNull()?.message ?: "service error"}")
                RecommendationApi.loadPendingAssignments { pendingResult ->
                    if (!isViewReady) return@loadPendingAssignments
                    val pending = pendingResult.fold(
                        onSuccess = { listOf("Pending assignment reviews" to it.size.toString()) },
                        onFailure = { listOf("Pending assignment reviews" to "Unavailable: ${it.message ?: "service error"}") }
                    )
                    val rows = crewStats + flightStats + pending
                    adapter.submit(rows)
                    showEmpty(false)
                    setSummary("Live operational totals · performance rates require validated historical reporting data")
                }
            }
        }
    }
}
