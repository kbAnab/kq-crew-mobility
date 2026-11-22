package com.example.kenyaairways.crewcontroller

import android.view.View
import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast

class AssignmentApprovalFragment : ListFragment("Assignment Approval") {

    private lateinit var adapter: RowAdapter<PendingAssignment>

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        adapter = RowAdapter(emptyList()) { row, p ->
            row.setTexts("${p.crewName} (${p.crewId})",
                "${p.flightNo.ifBlank { p.flightId }} · ${p.route} · ${p.qualification}",
                "Requested by: ${p.source}")
            row.badge("Pending", R.color.duty_reserve)
            row.accent(R.color.duty_reserve)
            row.actions.visibility = View.VISIBLE
            row.btnSecondary.visibility = View.VISIBLE
            row.btnSecondary.text = "Reject"
            row.btnPrimary.text = "Approve"
            row.btnSecondary.setOnClickListener { decide(p, false, row.btnPrimary, row.btnSecondary) }
            row.btnPrimary.setOnClickListener { decide(p, true, row.btnPrimary, row.btnSecondary) }
        }
        b.rvList.adapter = adapter
        refresh()
    }

    private fun decide(p: PendingAssignment, approve: Boolean, primary: View, secondary: View) {
        primary.isEnabled = false
        secondary.isEnabled = false
        RecommendationApi.decideAssignment(p.id, approve) { result ->
            if (!isViewReady) return@decideAssignment
            result.onSuccess {
                refresh()
                Snackbar.make(b.root, if (approve) "Assignment approved" else "Assignment rejected", Snackbar.LENGTH_SHORT).show()
            }.onFailure { error ->
                primary.isEnabled = true
                secondary.isEnabled = true
                Toast.makeText(requireContext(), "Decision could not be saved: ${error.message}", Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun refresh() {
        setSummary("Loading pending approvals…")
        RecommendationApi.loadPendingAssignments { result ->
            if (!isViewReady) return@loadPendingAssignments
            result.onSuccess { pending ->
                adapter.submit(pending)
                showEmpty(pending.isEmpty(), "No pending approvals")
                setSummary("${pending.size} pending assignments · decisions are saved by the crew service")
            }.onFailure {
                adapter.submit(emptyList())
                showEmpty(true, "Could not load approvals: ${it.message ?: "service unavailable"}")
                setSummary("Pending assignments unavailable")
            }
        }
    }
}
