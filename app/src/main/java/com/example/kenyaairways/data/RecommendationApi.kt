package com.example.kenyaairways.data

import android.os.Handler
import android.os.Looper
import com.example.kenyaairways.Candidate
import com.example.kenyaairways.AlertItem
import com.example.kenyaairways.AppNotification
import com.example.kenyaairways.CrewMember
import com.example.kenyaairways.ComplianceItem
import com.example.kenyaairways.ComplianceStatus
import com.example.kenyaairways.OpenFlight
import com.example.kenyaairways.PendingAssignment
import com.example.kenyaairways.Recommendation
import com.example.kenyaairways.Severity
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object RecommendationApi {

    var baseUrl = "http://10.0.2.2:8000"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun recommend(flightId: String, topN: Int?, onResult: (Result<List<Recommendation>>) -> Unit) {
        withToken(onResult) { token ->
            val body = JSONObject().put("flight_id", flightId)
            if (topN != null) body.put("top_n", topN)
            val response = JSONObject(call(token, "/recommend/flight", "POST", body))
            val array = response.getJSONArray("recommendations")
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val crewId = item.getString("crew_id")
                val score = item.getDouble("suitability_score")
                val rank = item.getInt("rank")
                require(crewId.isNotBlank() && score.isFinite() && rank > 0) {
                    "The recommendation service returned an invalid result."
                }
                Recommendation(
                    candidate = Candidate(
                        crewId = crewId,
                        name = item.optString("name", crewId),
                        qualification = item.getString("qualification"),
                        experience = item.getInt("experience_years"),
                        dutyHours = item.getDouble("duty_hours"),
                        restHours = item.getDouble("rest_hours"),
                        score = score
                    ),
                    rank = rank,
                    recommended = item.getString("recommendation") == "Recommended",
                    reason = item.optString("reason")
                )
            }
        }
    }

    fun loadFlights(onResult: (Result<List<OpenFlight>>) -> Unit) {
        withToken(onResult) { token ->
            val flights = JSONObject(call(token, "/flights", "GET"))
                .getJSONArray("flights")
            (0 until flights.length()).map { i ->
                val item = flights.getJSONObject(i)
                OpenFlight(
                    id = item.getString("id"),
                    flightNo = item.optString("flightNo", item.getString("id")),
                    route = item.optString("route"),
                    departure = item.optString("departure"),
                    qualification = item.getString("qualification"),
                    slots = item.getInt("slots"),
                    filled = item.getInt("filled"),
                    international = item.optBoolean("international", false)
                )
            }
        }
    }

    fun loadFlightCrew(flightNo: String, departureDay: String, onResult: (Result<List<CrewMember>>) -> Unit) {
        withToken(onResult) { token ->
            val encoded = java.net.URLEncoder.encode(flightNo, "UTF-8")
            val encodedDay = java.net.URLEncoder.encode(departureDay, "UTF-8")
            val array = JSONObject(call(token, "/flights/by-number/$encoded/crew?departure_day=$encodedDay", "GET"))
                .getJSONArray("crew")
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                CrewMember(
                    name = item.optString("name", item.getString("crew_id")),
                    role = item.optString("qualification"),
                    id = item.getString("crew_id")
                )
            }
        }
    }

    fun loadPendingAssignments(onResult: (Result<List<PendingAssignment>>) -> Unit) {
        withToken(onResult) { token ->
            val assignments = JSONObject(call(token, "/assignments/pending", "GET"))
                .getJSONArray("assignments")
            (0 until assignments.length()).map { i ->
                val item = assignments.getJSONObject(i)
                PendingAssignment(
                    crewId = item.getString("crewId"),
                    crewName = item.optString("crewName", item.getString("crewId")),
                    flightId = item.getString("flightId"),
                    source = item.optString("source"),
                    id = item.getString("id"),
                    flightNo = item.optString("flightNo"),
                    route = item.optString("route"),
                    qualification = item.optString("qualification")
                )
            }
        }
    }

    fun requestAssignment(
        flightId: String,
        crewId: String,
        source: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        withToken(onResult) { token ->
            postAction(token, "/assignments", JSONObject()
                .put("flight_id", flightId)
                .put("crew_id", crewId)
                .put("source", source))
        }
    }

    fun decideAssignment(assignmentId: String, approve: Boolean, onResult: (Result<Unit>) -> Unit) {
        withToken(onResult) { token ->
            postAction(token, "/assignments/${java.net.URLEncoder.encode(assignmentId, "UTF-8")}/decision",
                JSONObject().put("action", if (approve) "approve" else "reject"))
        }
    }

    fun loadAlerts(onResult: (Result<List<AlertItem>>) -> Unit) {
        withToken(onResult) { token ->
            val array = JSONObject(call(token, "/alerts", "GET")).getJSONArray("alerts")
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                AlertItem(
                    title = item.optString("title"),
                    detail = item.optString("detail"),
                    time = item.optString("time"),
                    severity = runCatching { Severity.valueOf(item.optString("severity")) }.getOrDefault(Severity.UNKNOWN),
                    acknowledged = item.optBoolean("acknowledged"),
                    id = item.getString("id")
                )
            }
        }
    }

    fun acknowledgeAlert(id: String, onResult: (Result<Unit>) -> Unit) {
        withToken(onResult) { token ->
            postAction(token, "/alerts/${java.net.URLEncoder.encode(id, "UTF-8")}/acknowledge")
        }
    }

    fun loadCompliance(onResult: (Result<List<ComplianceItem>>) -> Unit) {
        withToken(onResult) { token ->
            val array = JSONObject(call(token, "/compliance", "GET")).getJSONArray("records")
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                ComplianceItem(
                    crew = item.optString("crew"),
                    check = item.optString("check"),
                    detail = item.optString("detail"),
                    status = runCatching { ComplianceStatus.valueOf(item.optString("status")) }
                        .getOrDefault(ComplianceStatus.UNKNOWN),
                    id = item.getString("id")
                )
            }
        }
    }

    fun loadNotifications(onResult: (Result<List<AppNotification>>) -> Unit) {
        withToken(onResult) { token ->
            val array = JSONObject(call(token, "/notifications", "GET")).getJSONArray("notifications")
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                AppNotification(
                    title = item.optString("title"),
                    message = item.optString("message"),
                    time = item.optString("time"),
                    read = item.optBoolean("read"),
                    id = item.getString("id")
                )
            }
        }
    }

    fun markNotificationRead(id: String, onResult: (Result<Unit>) -> Unit) {
        withToken(onResult) { token ->
            postAction(token, "/notifications/${java.net.URLEncoder.encode(id, "UTF-8")}/read")
        }
    }

    fun loadReport(kind: String, onResult: (Result<List<Map<String, String>>>) -> Unit) {
        withToken(onResult) { token ->
            val rows = JSONObject(call(token, "/reports/${java.net.URLEncoder.encode(kind, "UTF-8")}", "GET"))
                .getJSONArray("rows")
            (0 until rows.length()).map { i ->
                val item = rows.getJSONObject(i)
                buildMap {
                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, if (item.isNull(key)) "" else item.get(key).toString())
                    }
                }
            }
        }
    }

    private fun <T> withToken(onResult: (Result<T>) -> Unit, action: (String) -> T) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onResult(Result.failure(IllegalStateException("Not signed in")))
            return
        }
        user.getIdToken(false).addOnCompleteListener { task ->
            val token = task.result?.token
            if (!task.isSuccessful || token == null) {
                onResult(Result.failure(IOException("Could not get a sign-in token")))
                return@addOnCompleteListener
            }
            executor.execute {
                val result = runCatching { action(token) }
                mainHandler.post { onResult(result) }
            }
        }
    }

    private fun call(token: String, path: String, method: String, body: JSONObject? = null): String {
        val endpoint = URL(baseUrl.trimEnd('/') + path)
        require(endpoint.protocol == "https" ||
                (endpoint.protocol == "http" && endpoint.host in setOf("10.0.2.2", "localhost"))) {
            "The recommendation service must use HTTPS outside the local emulator."
        }
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(errorDetail(text) ?: "Service returned HTTP $code")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun postAction(token: String, path: String, body: JSONObject? = null) {
        if (call(token, path, "POST", body).isBlank()) throw IOException("Service returned an empty response")
    }

    private fun errorDetail(body: String): String? =
        runCatching { JSONObject(body).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() }
}
