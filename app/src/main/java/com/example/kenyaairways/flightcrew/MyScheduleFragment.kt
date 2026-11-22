package com.example.kenyaairways.flightcrew

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kenyaairways.*
import com.example.kenyaairways.data.UserRepository
import com.example.kenyaairways.databinding.FragmentMyScheduleBinding
import com.example.kenyaairways.databinding.ItemDayBinding
import com.example.kenyaairways.databinding.ItemDutyBinding
import com.example.kenyaairways.databinding.ItemSectionHeaderBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone


class MyScheduleFragment : Fragment() {

    private var _b: FragmentMyScheduleBinding? = null
    private val b get() = _b!!

    private var registration: ListenerRegistration? = null
    private lateinit var dayAdapter: DayAdapter
    private lateinit var rosterAdapter: RosterAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMyScheduleBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        b.tvMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())
        val utc = SimpleDateFormat("HH:mm 'UTC'", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        b.tvUtc.text = utc.format(Date())

        dayAdapter = DayAdapter(emptyList())
        rosterAdapter = RosterAdapter(emptyList()) { duty ->
            if (duty.type == DutyType.FLIGHT) {
                (requireActivity() as MainActivity)
                    .open(AssignmentDetailsFragment.newInstance(
                        duty.title, duty.flightInfo, duty.start?.toDate()?.time
                    ), duty.title)
            }
        }
        b.rvDays.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.rvDays.adapter = dayAdapter
        b.rvRoster.layoutManager = LinearLayoutManager(requireContext())
        b.rvRoster.adapter = rosterAdapter

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            showMessage("Please log in to see your schedule.")
            return
        }
        showMessage("Loading your schedule…")
        registration = UserRepository.observeDuties(
            uid,
            onError = { message -> if (_b != null) showMessage(message) }
        ) { duties ->
            if (_b != null) render(duties)
        }
    }

    private fun showMessage(text: String?) {
        b.tvEmpty.visibility = if (text == null) View.GONE else View.VISIBLE
        b.tvEmpty.text = text
    }

    private fun render(duties: List<RosterItem.Duty>) {
        val zone = ZoneId.systemDefault()
        // Duties without a start time cannot be placed on a day, so they are skipped.
        val dated = duties
            .mapNotNull { d -> d.start?.toDate()?.toInstant()?.atZone(zone)?.toLocalDate()?.let { it to d } }
            .sortedBy { it.second.start!!.toDate() }

        if (dated.isEmpty()) {
            dayAdapter.submit(emptyList())
            rosterAdapter.submit(emptyList())
            showMessage("No duties scheduled yet.")
            return
        }
        showMessage(null)

        // highlight the next flight that has not finished yet
        val now = System.currentTimeMillis()
        val nextIndex = dated.indexOfFirst { (_, d) ->
            d.type == DutyType.FLIGHT && (d.end ?: d.start)!!.toDate().time >= now
        }
        val headerFormat = DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.getDefault())
        val items = mutableListOf<RosterItem>()
        var lastDate: LocalDate? = null
        dated.forEachIndexed { index, (date, duty) ->
            if (date != lastDate) {
                items.add(RosterItem.Header(date.format(headerFormat)))
                lastDate = date
            }
            items.add(if (index == nextIndex) duty.copy(highlight = true) else duty)
        }

        val today = LocalDate.now(zone)
        val days = dated.groupBy({ it.first }, { it.second }).map { (date, list) ->
            val status = when {
                list.any { it.type == DutyType.FLIGHT } -> DutyType.FLIGHT
                list.any { it.type == DutyType.RESERVE } -> DutyType.RESERVE
                else -> DutyType.OFF
            }
            DayCell(
                date.dayOfMonth.toString(),
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                status,
                date == today
            )
        }

        rosterAdapter.submit(items)
        dayAdapter.submit(days)
        b.rvDays.scrollToPosition(days.indexOfFirst { it.selected }.coerceAtLeast(0))
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setScreenTitle("My Schedule")
    }

    override fun onDestroyView() {
        registration?.remove()
        registration = null
        super.onDestroyView()
        _b = null
    }
}

class DayAdapter(private var days: List<DayCell>) : RecyclerView.Adapter<DayAdapter.VH>() {
    class VH(val b: ItemDayBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(newDays: List<DayCell>) { days = newDays; notifyDataSetChanged() }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemDayBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = days.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val d = days[i]; val ctx = h.itemView.context
        h.b.tvNumber.text = d.number
        h.b.tvWeekday.text = d.weekday
        h.b.root.setBackgroundColor(
            ContextCompat.getColor(ctx, if (d.selected) R.color.day_selected else android.R.color.transparent))
        val color = when (d.status) {
            DutyType.FLIGHT -> R.color.duty_flight
            DutyType.RESERVE -> R.color.duty_reserve
            DutyType.OFF -> R.color.duty_off
            null -> android.R.color.transparent
        }
        h.b.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, color))
    }
}

class RosterAdapter(
    private var items: List<RosterItem>,
    private val onDutyClick: (RosterItem.Duty) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object { const val HEADER = 0; const val DUTY = 1 }

    class HeaderVH(val b: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class DutyVH(val b: ItemDutyBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(newItems: List<RosterItem>) { items = newItems; notifyDataSetChanged() }

    override fun getItemViewType(position: Int) =
        if (items[position] is RosterItem.Header) HEADER else DUTY

    override fun onCreateViewHolder(p: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(p.context)
        return if (type == HEADER) HeaderVH(ItemSectionHeaderBinding.inflate(inf, p, false))
        else DutyVH(ItemDutyBinding.inflate(inf, p, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RosterItem.Header -> (holder as HeaderVH).b.tvHeader.text = item.date
            is RosterItem.Duty -> bindDuty((holder as DutyVH).b, item)
        }
    }

    private fun bindDuty(b: ItemDutyBinding, d: RosterItem.Duty) {
        val ctx = b.root.context
        val (iconRes, accent) = when (d.type) {
            DutyType.FLIGHT -> R.drawable.ic_flight to R.color.duty_flight
            DutyType.RESERVE -> R.drawable.ic_phone to R.color.duty_reserve
            DutyType.OFF -> R.drawable.ic_home to R.color.duty_off
        }
        b.ivIcon.setImageResource(iconRes)
        b.ivIcon.setColorFilter(ContextCompat.getColor(ctx, accent))
        b.accentBar.setBackgroundColor(ContextCompat.getColor(ctx, accent))

        b.tvTitle.text = d.title
        b.tvTime.text = d.time
        b.tvFlight.text = d.flightInfo

        val main = if (d.highlight) R.color.duty_reserve else R.color.text_primary
        b.tvTitle.setTextColor(ContextCompat.getColor(ctx, main))
        b.tvFlight.setTextColor(ContextCompat.getColor(ctx, main))
        b.tvTime.setTextColor(
            ContextCompat.getColor(ctx, if (d.highlight) R.color.duty_reserve else R.color.text_secondary)
        )

        b.root.setOnClickListener { onDutyClick(d) }
    }
}
