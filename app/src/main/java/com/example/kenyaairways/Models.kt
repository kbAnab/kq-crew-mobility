package com.example.kenyaairways

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.firebase.Timestamp

enum class Role(val label: String) {
    FLIGHT_CREW("Flight Crew"),
    CREW_CONTROLLER("Crew Controller"),
    MANAGER("Manager"),
    ADMIN("Administrator")
}

data class User(val name: String, val email: String, val role: Role)


object Session { var user: User? = null }

enum class DutyType { FLIGHT, RESERVE, OFF }

sealed class RosterItem {
    data class Header(val date: String) : RosterItem()
    data class Duty(
        val type: DutyType,
        val title: String,
        val time: String,
        val flightInfo: String = "",
        val highlight: Boolean = false,
        val start: Timestamp? = null,
        val end: Timestamp? = null
    ) : RosterItem()
}

data class DayCell(val number: String, val weekday: String, val status: DutyType?, val selected: Boolean = false)

data class CrewMember(
    val name: String, val role: String, val id: String,
    val hasComment: Boolean = false, val commonEvents: Int = 0
)

object SampleData {
    val days = listOf(
        DayCell("4", "We", DutyType.FLIGHT, true), DayCell("5", "Th", DutyType.FLIGHT),
        DayCell("6", "Fr", DutyType.RESERVE), DayCell("7", "Sa", DutyType.OFF),
        DayCell("8", "Su", DutyType.OFF), DayCell("9", "Mo", DutyType.FLIGHT),
        DayCell("10", "Tu", DutyType.FLIGHT)
    )

    val roster = listOf(
        RosterItem.Header("We. 04.11."),
        RosterItem.Duty(DutyType.FLIGHT, "NBO – MBA", "14:05 - 15:15", "KQ600 / 5Y-KYA", true),
        RosterItem.Duty(DutyType.FLIGHT, "MBA – NBO", "16:00 - 17:10", "KQ601 / 5Y-KYA"),
        RosterItem.Header("Th. 05.11."),
        RosterItem.Duty(DutyType.FLIGHT, "NBO – JRO", "08:30 - 09:35", "KQ472 / 5Y-KZB"),
        RosterItem.Duty(DutyType.FLIGHT, "JRO – NBO", "10:20 - 11:25", "KQ473 / 5Y-KZB"),
        RosterItem.Duty(DutyType.FLIGHT, "NBO – EBB", "14:00 - 15:20", "KQ412 / 5Y-KZB"),
        RosterItem.Header("Fr. 06.11."),
        RosterItem.Duty(DutyType.RESERVE, "RESV", "00:00 - 23:59"),
        RosterItem.Header("Sa. 07.11."),
        RosterItem.Duty(DutyType.OFF, "OFF", "00:00 - 23:59"),
        RosterItem.Header("Su. 08.11."),
        RosterItem.Duty(DutyType.OFF, "OFF", "00:00 - 23:59")
    )

    val cockpit = listOf(
        CrewMember("Claus Harder", "PIC", "QUAX747"),
        CrewMember("Evelyn Bouchard", "FO", "BOCD", true, 8)
    )
    val cabin = listOf(
        CrewMember("Phoebe Thompson", "P1", "T7H", false, 14),
        CrewMember("Me", "FA", "SDR"),
        CrewMember("Gabriel Schmitt", "FA", "SM3", true, 6),
        CrewMember("Lin Koyama", "FA", "KYL", true, 2)
    )
}



enum class Severity(val label: String) { HIGH("High"), MEDIUM("Medium"), LOW("Low"), UNKNOWN("Unknown") }
enum class ComplianceStatus(val label: String) { OK("Compliant"), DUE_SOON("Due soon"), BREACH("Breach"), UNKNOWN("Unknown") }

data class AppNotification(val title: String, val message: String, val time: String, var read: Boolean = false, val id: String = "")
data class AlertItem(val title: String, val detail: String, val time: String, val severity: Severity, var acknowledged: Boolean = false, val id: String = "")
data class ComplianceItem(val crew: String, val check: String, val detail: String, val status: ComplianceStatus, val id: String = "")
data class KpiItem(val name: String, val description: String, val percent: Int)
data class ManagedUser(val name: String, val email: String, var role: Role, var active: Boolean)
data class UtilisationRow(val name: String, val role: String, val dutyHours: Double, val daysOff: Int)

data class OpenFlight(
    val id: String, val flightNo: String, val route: String, val departure: String,
    val qualification: String, val slots: Int, var filled: Int, val international: Boolean = false
)
data class Candidate(
    val crewId: String, val name: String, val qualification: String,
    val experience: Int, val dutyHours: Double, val restHours: Double, val score: Double
)
data class Recommendation(val candidate: Candidate, val rank: Int, val recommended: Boolean, val reason: String)
data class PendingAssignment(
    val crewId: String,
    val crewName: String,
    val flightId: String,
    val source: String,
    val id: String = "",
    val flightNo: String = "",
    val route: String = "",
    val qualification: String = ""
)
data class DecisionRecord(val crewName: String, val flightNo: String, val route: String, val decision: String, val time: String)


data class Policy(val maxDutyHours: Float, val minRestHours: Float, val minExperienceYears: Int)

object PolicyStore {
    private fun prefs(c: Context) = c.getSharedPreferences("policies", Context.MODE_PRIVATE)
    fun defaults(international: Boolean) =
        if (international) Policy(11f, 12f, 3) else Policy(12f, 10f, 1)

    fun load(c: Context, international: Boolean): Policy {
        val k = if (international) "intl" else "dom"
        val d = defaults(international)
        val p = prefs(c)
        return Policy(p.getFloat(k + "_duty", d.maxDutyHours), p.getFloat(k + "_rest", d.minRestHours),
            p.getInt(k + "_exp", d.minExperienceYears))
    }

    fun save(c: Context, international: Boolean, policy: Policy) {
        val k = if (international) "intl" else "dom"
        prefs(c).edit().putFloat(k + "_duty", policy.maxDutyHours).putFloat(k + "_rest", policy.minRestHours)
            .putInt(k + "_exp", policy.minExperienceYears).apply()
    }

    fun reset(c: Context) = prefs(c).edit().clear().apply()
}


fun interface CrewRecommendationSource {
    fun recommend(flight: OpenFlight, policy: Policy): List<Recommendation>
}

object SampleRecommendationSource : CrewRecommendationSource {
    override fun recommend(flight: OpenFlight, policy: Policy): List<Recommendation> {
        val needed = (flight.slots - flight.filled).coerceAtLeast(1)
        return DemoData.candidates
            .filter { it.qualification == flight.qualification }

            .filter { it.dutyHours < policy.maxDutyHours && it.restHours >= policy.minRestHours &&
                    it.experience >= policy.minExperienceYears }
            .sortedByDescending { it.score }
            .mapIndexed { i, c ->
                Recommendation(c, i + 1, i < needed,
                    "${c.experience} yrs experience, ${c.restHours} h rest, ${c.dutyHours} h duty")
            }
    }
}

object ControllerStore {
    val flights = mutableListOf(
        OpenFlight("KQ600-CPT", "KQ600", "NBO – MBA", "05 Nov 14:05", "Captain", 1, 0),
        OpenFlight("KQ600-FO", "KQ600", "NBO – MBA", "05 Nov 14:05", "First Officer", 1, 1),
        OpenFlight("KQ600-CC", "KQ600", "NBO – MBA", "05 Nov 14:05", "Cabin Crew", 4, 2),
        OpenFlight("KQ472-CC", "KQ472", "NBO – JRO", "05 Nov 08:30", "Cabin Crew", 4, 4),
        OpenFlight("KQ412-CPT", "KQ412", "NBO – EBB", "06 Nov 14:00", "Captain", 1, 0),
        OpenFlight("KQ100-FO", "KQ100", "NBO – LHR", "06 Nov 22:30", "First Officer", 1, 0, true)
    )
    val pending = mutableListOf(
        PendingAssignment("C0102", "Wanjiru Kamau", "KQ412-CPT", "Manual request"),
        PendingAssignment("C0233", "Grace Njeri", "KQ600-CC", "AI recommender")
    )
    val history = mutableListOf<DecisionRecord>()

    fun flight(id: String) = flights.firstOrNull { it.id == id }

    fun requestAssignment(f: OpenFlight, c: Candidate, source: String) {
        if (pending.none { it.crewId == c.crewId && it.flightId == f.id })
            pending.add(PendingAssignment(c.crewId, c.name, f.id, source))
    }


    fun decide(p: PendingAssignment, approve: Boolean): String {
        val f = flight(p.flightId)
        if (approve && f != null && f.filled >= f.slots) return f.flightNo + " is already fully crewed"
        pending.remove(p)
        if (approve && f != null) f.filled++
        val time = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val word = if (approve) "Approved" else "Rejected"
        history.add(DecisionRecord(p.crewName, f?.flightNo ?: p.flightId, f?.route ?: "", word, time))
        return word + " " + p.crewName
    }
}

enum class ReportKind { UTILISATION, COMPLIANCE, ASSIGNMENTS, ALERTS }
data class ReportType(val kind: ReportKind, val title: String, val description: String)

object ReportBuilder {
    private fun line(vararg f: Any) =
        f.joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }

    fun build(kind: ReportKind): String = when (kind) {
        ReportKind.UTILISATION ->
            (listOf(line("Name", "Role", "Duty hours (month)", "Days off")) +
                    DemoData.utilisation.map { line(it.name, it.role, it.dutyHours, it.daysOff) })
        ReportKind.COMPLIANCE ->
            (listOf(line("Crew", "Check", "Detail", "Status")) +
                    DemoData.compliance.map { line(it.crew, it.check, it.detail, it.status.label) })
        ReportKind.ASSIGNMENTS ->
            (listOf(line("Crew", "Flight", "Route", "Decision", "Time")) +
                    ControllerStore.history.map { line(it.crewName, it.flightNo, it.route, it.decision, it.time) } +
                    ControllerStore.pending.map { line(it.crewName, ControllerStore.flight(it.flightId)?.flightNo ?: it.flightId,
                        ControllerStore.flight(it.flightId)?.route ?: "", "Pending", "") })
        ReportKind.ALERTS ->
            (listOf(line("Alert", "Detail", "Severity", "Time", "Acknowledged")) +
                    DemoData.alerts.map { line(it.title, it.detail, it.severity.label, it.time, it.acknowledged) })
    }.joinToString("\n")
}

object DemoData {
    val notifications = mutableListOf(
        AppNotification("Roster change", "KQ472 NBO – JRO on 05 Nov now departs 08:30.", "10 min ago"),
        AppNotification("Duty reminder", "Report for KQ600 NBO – MBA at 12:35 today.", "1 h ago"),
        AppNotification("Assignment approved", "You were confirmed as cabin crew on KQ412 NBO – EBB.", "Yesterday", true),
        AppNotification("Training due", "Safety equipment training expires in 30 days.", "2 days ago", true)
    )
    val alerts = mutableListOf(
        AlertItem("Duty limit exceeded", "FO Evelyn Bouchard is 1.5 h over the planned duty limit on KQ600.", "09:40", Severity.HIGH),
        AlertItem("Crew no-show", "FA Gabriel Schmitt has not checked in for KQ472.", "08:15", Severity.HIGH),
        AlertItem("Flight delay", "KQ412 NBO – EBB delayed by 55 minutes; crew duty time affected.", "07:50", Severity.MEDIUM),
        AlertItem("Standby crew low", "Only 2 cabin crew on standby at NBO tonight.", "Yesterday", Severity.MEDIUM),
        AlertItem("Roster published", "Weekly roster 09–15 Nov is ready for review.", "Yesterday", Severity.LOW)
    )
    val compliance = listOf(
        ComplianceItem("Evelyn Bouchard (FO)", "Proficiency check", "Expired 02 Nov", ComplianceStatus.BREACH),
        ComplianceItem("Mercy Wafula (FA)", "Rest before duty", "Below the internal minimum on KQ472", ComplianceStatus.BREACH),
        ComplianceItem("Claus Harder (Captain)", "Medical certificate", "Expires 30 Nov", ComplianceStatus.DUE_SOON),
        ComplianceItem("Peter Otieno (Captain)", "Recurrent training", "Due 20 Nov", ComplianceStatus.DUE_SOON),
        ComplianceItem("Samuel Mwangi (Captain)", "Licence validity", "Due 05 Dec", ComplianceStatus.DUE_SOON),
        ComplianceItem("Lin Koyama (FA)", "Safety equipment training", "Valid until 14 Mar 2027", ComplianceStatus.OK),
        ComplianceItem("Phoebe Thompson (FA)", "Medical certificate", "Valid until 02 Aug 2027", ComplianceStatus.OK),
        ComplianceItem("Brian Mutua (FO)", "Proficiency check", "Valid until 11 Jun 2027", ComplianceStatus.OK)
    )
    val kpis = listOf(
        KpiItem("Crew report on time", "Crew reporting for duty on time", 96),
        KpiItem("Duty-limit compliance", "Duties within the internal duty limits", 98),
        KpiItem("Roster utilisation", "Share of available crew hours rostered", 82),
        KpiItem("Positions filled", "Flight crew positions filled 24 h before departure", 91),
        KpiItem("Approvals within SLA", "Assignments approved within the target time", 74)
    )
    val users = mutableListOf(
        ManagedUser("Claus Harder", "claus.harder@example.com", Role.FLIGHT_CREW, true),
        ManagedUser("Wanjiru Kamau", "wanjiru.kamau@example.com", Role.FLIGHT_CREW, true),
        ManagedUser("Amina Hassan", "amina.hassan@example.com", Role.CREW_CONTROLLER, true),
        ManagedUser("Daniel Kiptoo", "daniel.kiptoo@example.com", Role.CREW_CONTROLLER, false),
        ManagedUser("Grace Wambui", "grace.wambui@example.com", Role.MANAGER, true)
    )
    val reportTypes = listOf(
        ReportType(ReportKind.UTILISATION, "Crew duty and availability", "Current duty/rest hours and availability on the crew record"),
        ReportType(ReportKind.COMPLIANCE, "Compliance summary", "Licence, medical, training and rest checks"),
        ReportType(ReportKind.ASSIGNMENTS, "Assignment decisions", "Approved, rejected and pending assignments"),
        ReportType(ReportKind.ALERTS, "Alert log", "Operational alerts and acknowledgements")
    )
    val utilisation = listOf(
        UtilisationRow("Claus Harder", "Captain", 62.5, 9),
        UtilisationRow("Evelyn Bouchard", "First Officer", 58.0, 10),
        UtilisationRow("Phoebe Thompson", "Cabin Crew", 71.5, 7),
        UtilisationRow("Gabriel Schmitt", "Cabin Crew", 66.0, 8),
        UtilisationRow("Lin Koyama", "Cabin Crew", 54.5, 11)
    )
    val candidates = listOf(
        Candidate("C0101", "Claus Harder", "Captain", 24, 2.5, 14.0, 91.0),
        Candidate("C0102", "Wanjiru Kamau", "Captain", 18, 4.0, 13.2, 84.0),
        Candidate("C0103", "Peter Otieno", "Captain", 12, 6.0, 11.5, 66.0),
        Candidate("C0104", "Samuel Mwangi", "Captain", 9, 9.0, 8.5, 40.0),
        Candidate("C0201", "Evelyn Bouchard", "First Officer", 8, 3.0, 15.0, 88.0),
        Candidate("C0202", "Brian Mutua", "First Officer", 5, 5.5, 12.5, 71.0),
        Candidate("C0203", "Amina Hassan", "First Officer", 3, 7.0, 10.5, 55.0),
        Candidate("C0204", "Kevin Omondi", "First Officer", 2, 4.0, 13.0, 62.0),
        Candidate("C0231", "Phoebe Thompson", "Cabin Crew", 14, 3.0, 14.5, 90.0),
        Candidate("C0232", "Gabriel Schmitt", "Cabin Crew", 9, 4.5, 13.0, 80.0),
        Candidate("C0233", "Grace Njeri", "Cabin Crew", 11, 7.5, 10.8, 58.0),
        Candidate("C0234", "Lin Koyama", "Cabin Crew", 4, 5.0, 12.0, 70.0),
        Candidate("C0235", "Faith Achieng", "Cabin Crew", 2, 2.0, 14.0, 76.0),
        Candidate("C0236", "Daniel Kiptoo", "Cabin Crew", 7, 6.5, 11.2, 62.0),
        Candidate("C0237", "Mercy Wafula", "Cabin Crew", 6, 11.0, 9.0, 35.0)
    )
}
