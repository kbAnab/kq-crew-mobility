package com.example.kenyaairways.manager

import android.content.Intent
import android.view.View
import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi
import android.widget.Toast

class ReportsFragment : ListFragment("Reports") {

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        setSummary("Reports are generated from current Firestore records and shared as CSV.")
        b.rvList.adapter = RowAdapter(DemoData.reportTypes) { row, r ->
            row.setTexts(r.title, r.description)
            row.accent(R.color.duty_flight)
            row.actions.visibility = View.VISIBLE
            row.btnPrimary.text = "Generate & share"
            row.btnPrimary.setOnClickListener { share(r) }
        }
    }

    private fun share(r: ReportType) {
        val key = when (r.kind) {
            ReportKind.UTILISATION -> "utilisation"
            ReportKind.COMPLIANCE -> "compliance"
            ReportKind.ASSIGNMENTS -> "assignments"
            ReportKind.ALERTS -> "alerts"
        }
        setSummary("Loading ${r.title.lowercase()} report…")
        RecommendationApi.loadReport(key) { result ->
            if (!isViewReady) return@loadReport
            result.onSuccess { rows ->
                val headers = rows.firstOrNull()?.keys?.toList() ?: when (key) {
                    "utilisation" -> listOf("crew_id", "name", "qualification", "duty_hours", "rest_hours", "available")
                    "compliance" -> listOf("crew_id", "crew", "check", "detail", "status")
                    "assignments" -> listOf("crew_id", "crew_name", "flight_id", "status", "source", "requested_at")
                    else -> listOf("title", "detail", "severity", "acknowledged", "created_at")
                }
                val csv = buildString {
                    appendLine(headers.joinToString(",") { csvCell(it) })
                    rows.forEach { row -> appendLine(headers.joinToString(",") { csvCell(row[it].orEmpty()) }) }
                }
                if (rows.isEmpty()) Toast.makeText(requireContext(), "No records are available for this report.", Toast.LENGTH_SHORT).show()
                setSummary("${rows.size} live records in ${r.title.lowercase()} report")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, r.title)
                    putExtra(Intent.EXTRA_TEXT, csv)
                }
                startActivity(Intent.createChooser(send, "Share ${r.title}"))
            }.onFailure {
                setSummary("Could not load report data: ${it.message ?: "service unavailable"}")
            }
        }
    }

    private fun csvCell(value: String) = "\"${value.replace("\"", "\"\"")}\""
}
