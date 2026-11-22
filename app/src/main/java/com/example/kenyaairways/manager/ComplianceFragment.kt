package com.example.kenyaairways.manager

import com.example.kenyaairways.*
import com.example.kenyaairways.data.RecommendationApi

class ComplianceFragment : ListFragment("Compliance") {

    private lateinit var adapter: RowAdapter<ComplianceItem>
    private var records: List<ComplianceItem> = emptyList()
    private var loadError: String? = null
    private var filter = 0

    private fun color(s: ComplianceStatus) = when (s) {
        ComplianceStatus.BREACH -> R.color.primary
        ComplianceStatus.DUE_SOON -> R.color.duty_reserve
        ComplianceStatus.OK -> R.color.duty_off
        ComplianceStatus.UNKNOWN -> R.color.text_secondary
    }

    override fun onReady() {
        RecommendationApi.baseUrl = requireContext().getString(R.string.recommendation_base_url).trimEnd('/')
        adapter = RowAdapter(emptyList()) { row, c ->
            row.setTexts(c.crew, c.check, c.detail)
            row.badge(c.status.label, color(c.status))
            row.accent(color(c.status))
        }
        b.rvList.adapter = adapter
        setupChips(listOf("All") + ComplianceStatus.entries.map { it.label }) { index ->
            filter = index
            render()
        }
        RecommendationApi.loadCompliance { result ->
            if (!isViewReady) return@loadCompliance
            result.onSuccess { records = it; loadError = null }.onFailure {
                records = emptyList()
                loadError = "Could not load compliance data: ${it.message ?: "service unavailable"}"
            }
            render()
        }
    }

    private fun render() {
        val all = records
        val list = if (filter <= 0) all else all.filter { it.status == ComplianceStatus.entries.getOrNull(filter - 1) }
        adapter.submit(list)
        showEmpty(list.isEmpty(), loadError ?: "Nothing in this category")
        setSummary("${all.count { it.status == ComplianceStatus.BREACH }} breaches · " +
                "${all.count { it.status == ComplianceStatus.DUE_SOON }} due soon · " +
                "${all.count { it.status == ComplianceStatus.OK }} compliant · live records")
    }
}
