package com.example.kenyaairways.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.Role
import com.example.kenyaairways.Session
import com.example.kenyaairways.databinding.FragmentDashboardBinding
import com.example.kenyaairways.databinding.ItemStatBinding
import com.example.kenyaairways.data.UserRepository
import com.example.kenyaairways.data.RecommendationApi

class DashboardFragment : Fragment() {
    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDashboardBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        val user = Session.user ?: return
        b.tvGreeting.text = "Jambo, ${user.name}"
        b.tvRole.text = user.role.label

        renderStats(listOf("Operational data" to "Loading…"))
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            renderStats(listOf("Operational data" to "Sign in to load your information"))
            return
        }
        RecommendationApi.baseUrl = requireContext().getString(com.example.kenyaairways.R.string.recommendation_base_url).trimEnd('/')
        UserRepository.loadDashboardStats(user.role, uid) { result ->
            if (_b == null) return@loadDashboardStats
            val stats = result.getOrElse {
                listOf("Crew data" to "Could not load data. Check connection and Firestore permissions.")
            }
            if (user.role == Role.FLIGHT_CREW) {
                renderStats(stats)
            } else {
                RecommendationApi.loadFlights { flightResult ->
                    if (_b == null) return@loadFlights
                    val flightStats = flightResult.fold(
                        onSuccess = { flights -> listOf(
                            "Flights in schedule" to flights.size.toString(),
                            "Open crew positions" to flights.sumOf { (it.slots - it.filled).coerceAtLeast(0) }.toString()
                        ) },
                        onFailure = { listOf("Flight schedule" to "Unavailable: ${it.message ?: "service error"}") }
                    )
                    RecommendationApi.loadPendingAssignments { assignmentResult ->
                        if (_b == null) return@loadPendingAssignments
                        val pendingStats = assignmentResult.fold(
                            onSuccess = { listOf("Pending assignment reviews" to it.size.toString()) },
                            onFailure = { listOf("Pending assignment reviews" to "Unavailable: ${it.message ?: "service error"}") }
                        )
                        renderStats(stats + flightStats + pendingStats)
                    }
                }
            }
        }
    }

    private fun renderStats(stats: List<Pair<String, String>>) {
        val container = _b?.statsContainer ?: return
        container.removeAllViews()
        stats.forEach { (label, value) ->
            val item = ItemStatBinding.inflate(layoutInflater, container, false)
            item.tvLabel.text = label
            item.tvValue.text = value
            container.addView(item.root)
        }
    }

    override fun onResume() { super.onResume(); (activity as? MainActivity)?.setScreenTitle("Dashboard") }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
