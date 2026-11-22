package com.example.kenyaairways.flightcrew

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kenyaairways.CrewMember
import com.example.kenyaairways.RowAdapter
import com.example.kenyaairways.setTexts
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.data.RecommendationApi
import com.example.kenyaairways.databinding.FragmentAssignmentDetailsBinding
import com.example.kenyaairways.databinding.ItemCrewMemberBinding
import com.example.kenyaairways.databinding.ItemSectionHeaderBinding
import com.google.android.material.tabs.TabLayout
import java.time.Instant
import java.time.ZoneId

class AssignmentDetailsFragment : Fragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_INFO = "info"
        private const val ARG_START = "start"
        fun newInstance(title: String, flightInfo: String, startMillis: Long? = null) = AssignmentDetailsFragment().apply {
            arguments = bundleOf(ARG_TITLE to title, ARG_INFO to flightInfo, ARG_START to startMillis)
        }
    }

    private var _b: FragmentAssignmentDetailsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentAssignmentDetailsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        RecommendationApi.baseUrl = requireContext().getString(com.example.kenyaairways.R.string.recommendation_base_url).trimEnd('/')
        b.tvFlightInfo.text = requireArguments().getString(ARG_INFO)

        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val info = requireArguments().getString(ARG_INFO).orEmpty()
        val crewAdapter = CrewAdapter(emptyList())
        var currentTab = 0
        var crewRows: List<CrewMember> = emptyList()
        var crewError: String? = "Loading confirmed crew assignments…"
        b.rvCrew.layoutManager = LinearLayoutManager(requireContext())
        b.rvCrew.adapter = crewAdapter

        listOf("Flight", "Crew", "Pax", "Trip").forEach { b.tabs.addTab(b.tabs.newTab().setText(it)) }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                b.rvCrew.adapter = when (tab.position) {
                    0 -> infoAdapter(listOf(
                        "Route" to title, "Flight / aircraft" to info,
                        "Operational status" to "Not included in this duty record"))
                    1 -> {
                        crewAdapter.submit(crewRows)
                        b.tvEmpty.visibility = if (crewError != null || crewRows.isEmpty()) View.VISIBLE else View.GONE
                        b.tvEmpty.text = crewError ?: "No approved crew assignments are available for this flight."
                        crewAdapter
                    }
                    2 -> infoAdapter(listOf("Passenger data" to "No passenger manifest service is configured."))
                    else -> infoAdapter(listOf("Trip data" to "Trip history is not linked to this duty record."))
                }
                if (tab.position != 1) b.tvEmpty.visibility = View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        b.tabs.getTabAt(0)?.select()
        val flightNo = info.substringBefore('/').trim()
        val startMillis = requireArguments().getLong(ARG_START, -1L)
        val departureDay = if (startMillis >= 0) {
            Instant.ofEpochMilli(startMillis).atZone(ZoneId.of("Africa/Nairobi")).toLocalDate().toString()
        } else null
        if (flightNo.isNotBlank() && departureDay != null) {
            RecommendationApi.loadFlightCrew(flightNo, departureDay) { result ->
                if (_b == null) return@loadFlightCrew
                result.onSuccess { crewRows = it; crewError = null }.onFailure {
                    crewRows = emptyList()
                    crewError = "Could not load assigned crew: ${it.message ?: "service unavailable"}"
                }
                if (currentTab == 1) {
                    crewAdapter.submit(crewRows)
                    b.tvEmpty.visibility = if (crewError != null || crewRows.isEmpty()) View.VISIBLE else View.GONE
                    b.tvEmpty.text = crewError ?: "No approved crew assignments are available for this flight."
                }
            }
        } else {
            crewError = "This duty record does not contain a flight number and departure date."
        }
    }

    private fun infoAdapter(rows: List<Pair<String, String>>) =
        RowAdapter(rows) { row, (label, value) -> row.setTexts(label, value) }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setScreenTitle(requireArguments().getString(ARG_TITLE) ?: "Assignment")
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

sealed class CrewRow {
    data class Header(val text: String) : CrewRow()
    data class Member(val member: CrewMember) : CrewRow()
}

class CrewAdapter(private var rows: List<CrewRow>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    class HeaderVH(val b: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class MemberVH(val b: ItemCrewMemberBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(members: List<CrewMember>) {
        rows = listOf(CrewRow.Header("ASSIGNED CREW")) + members.map { CrewRow.Member(it) }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (rows[position] is CrewRow.Header) 0 else 1
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(p: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(p.context)
        return if (type == 0) HeaderVH(ItemSectionHeaderBinding.inflate(inf, p, false))
        else MemberVH(ItemCrewMemberBinding.inflate(inf, p, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CrewRow.Header -> (holder as HeaderVH).b.tvHeader.text = row.text
            is CrewRow.Member -> {
                val b = (holder as MemberVH).b; val m = row.member
                b.tvName.text = "${m.role} ${m.name}"
                b.ivComment.visibility = if (m.hasComment) View.VISIBLE else View.GONE
                b.tvSub.text = if (m.hasComment) "Comment · ID: ${m.id}" else "ID: ${m.id}"
                b.tvCommon.visibility = if (m.commonEvents > 0) View.VISIBLE else View.GONE
                b.tvCommon.text = "${m.commonEvents} common events"

            }
        }
    }
}
