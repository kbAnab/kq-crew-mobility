package com.example.kenyaairways.data

import com.example.kenyaairways.DutyType
import com.example.kenyaairways.Role
import com.example.kenyaairways.RosterItem
import com.example.kenyaairways.Policy
import com.example.kenyaairways.PolicyStore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar
import java.util.Locale



data class UserProfile(
    val name: String,
    val email: String,
    val role: Role
)

data class ManagedUserProfile(
    val uid: String,
    val name: String,
    val email: String,
    val role: Role,
    val active: Boolean,
    val requestedRole: Role?
)

data class PolicySet(val domestic: Policy, val international: Policy)

object UserRepository {

    private val db get() = FirebaseFirestore.getInstance()

    fun createProfile(
        uid: String,
        name: String,
        email: String,
        requestedRole: Role,
        onDone: (Result<Unit>) -> Unit
    ) {
        val ref = db.collection("users").document(uid)
        db.runTransaction { transaction ->
            check(!transaction.get(ref).exists()) { "A profile already exists for this account." }
            val profile = mutableMapOf<String, Any>(
                "name" to name,
                "email" to email,
                "role" to Role.FLIGHT_CREW.name,
                "active" to true,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (requestedRole != Role.FLIGHT_CREW) profile["requestedRole"] = requestedRole.name
            transaction.set(ref, profile)
        }.addOnSuccessListener { onDone(Result.success(Unit)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }



    fun loadProfile(uid: String, onResult: (Result<UserProfile?>) -> Unit) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) {
                    onResult(Result.success(null))
                    return@addOnSuccessListener
                }

                val active = doc.getBoolean("active") == true
                val name = doc.getString("name").orEmpty()
                val email = doc.getString("email").orEmpty()

                val role = doc.getString("role")?.let { storedRole ->
                    runCatching {
                        Role.valueOf(storedRole)
                    }.getOrNull()
                }

                if (active && name.isNotBlank() &&
                    email.isNotBlank() && role != null
                ) {
                    onResult(Result.success(
                        UserProfile(
                            name = name,
                            email = email,
                            role = role
                        )
                    ))
                } else {
                    onResult(Result.success(null))
                }
            }
            .addOnFailureListener { error ->
                onResult(Result.failure(error))
            }
    }

    fun loadManagedUsers(onResult: (Result<List<ManagedUserProfile>>) -> Unit) {
        db.collection("users").get()
            .addOnSuccessListener { snapshot ->
                val users = snapshot.documents.mapNotNull { document ->
                    val role = document.getString("role")?.let { value ->
                        runCatching { Role.valueOf(value) }.getOrNull()
                    } ?: return@mapNotNull null
                    val requestedRole = document.getString("requestedRole")?.let { value ->
                        runCatching { Role.valueOf(value) }.getOrNull()
                    }
                    ManagedUserProfile(
                        uid = document.id,
                        name = document.getString("name").orEmpty(),
                        email = document.getString("email").orEmpty(),
                        role = role,
                        active = document.getBoolean("active") == true,
                        requestedRole = requestedRole
                    )
                }
                onResult(Result.success(users))
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun updateManagedUser(
        uid: String,
        role: Role,
        active: Boolean,
        clearRoleRequest: Boolean = false,
        onDone: (Result<Unit>) -> Unit
    ) {
        val data = hashMapOf<String, Any>(
            "role" to role.name,
            "active" to active,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (clearRoleRequest) data["requestedRole"] = FieldValue.delete()
        db.collection("users").document(uid).update(data)
            .addOnSuccessListener { onDone(Result.success(Unit)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }

    fun loadDashboardStats(
        role: Role,
        uid: String,
        onResult: (Result<List<Pair<String, String>>>) -> Unit
    ) {
        if (role == Role.FLIGHT_CREW) {
            val dutiesRef = db.collection("users").document(uid).collection("duties")
            dutiesRef.get().addOnSuccessListener { duties ->
                val now = System.currentTimeMillis()
                val monthStart = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val upcoming = duties.documents.count {
                    (it.getTimestamp("end") ?: it.getTimestamp("start"))?.toDate()?.time?.let { t -> t >= now } == true
                }
                val hours = duties.documents.sumOf { document ->
                    val start = document.getTimestamp("start")?.toDate()?.time
                    val end = document.getTimestamp("end")?.toDate()?.time
                    if (start != null && end != null && start >= monthStart && end >= start) (end - start) / 3_600_000.0 else 0.0
                }
                db.collection("availability").document(uid).get()
                    .addOnSuccessListener { availability ->
                        val standby = availability.getBoolean("standby")?.let { if (it) "Available" else "Not available" } ?: "Not set"
                        onResult(Result.success(listOf(
                            "Upcoming duties" to upcoming.toString(),
                            "Duty hours this month" to String.format(Locale.getDefault(), "%.1f", hours),
                            "Standby preference" to standby
                        )))
                    }
                    .addOnFailureListener { onResult(Result.failure(it)) }
            }.addOnFailureListener { onResult(Result.failure(it)) }
            return
        }

        db.collection("users").get().addOnSuccessListener { users ->
            db.collection("availability").get()
                .addOnSuccessListener { availability ->
                    val active = users.documents.filter { it.getBoolean("active") == true }
                    val activeCrewIds = active.filter { it.getString("role") == Role.FLIGHT_CREW.name }
                        .map { it.id }.toSet()
                    val activeCrewWithAvailability = availability.documents.count { it.id in activeCrewIds }
                    val stats = when (role) {
                        Role.CREW_CONTROLLER -> listOf(
                            "Active crew members" to activeCrewIds.size.toString(),
                            "Crew with availability on file" to activeCrewWithAvailability.toString(),
                            "Flight allocation data" to "Not configured"
                        )
                        Role.MANAGER -> listOf(
                            "Active crew members" to activeCrewIds.size.toString(),
                            "Crew with availability on file" to activeCrewWithAvailability.toString(),
                            "Schedule performance" to "Not configured"
                        )
                        Role.ADMIN -> listOf(
                            "Active accounts" to active.size.toString(),
                            "Inactive accounts" to (users.size() - active.size).toString(),
                            "Pending role requests" to users.documents.count { !it.getString("requestedRole").isNullOrBlank() }.toString()
                        )
                        Role.FLIGHT_CREW -> emptyList()
                    }
                    onResult(Result.success(stats))
                }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }.addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun loadPolicies(onResult: (Result<PolicySet>) -> Unit) {
        db.collection("settings").document("policies").get()
            .addOnSuccessListener { document ->
                val policies = document.get("policies") as? Map<*, *>
                fun read(key: String, fallback: Policy): Policy {
                    val values = policies?.get(key) as? Map<*, *> ?: return fallback
                    fun number(field: String, default: Number): Number = values[field] as? Number ?: default
                    return Policy(
                        maxDutyHours = number("maxDutyHours", fallback.maxDutyHours).toFloat(),
                        minRestHours = number("minRestHours", fallback.minRestHours).toFloat(),
                        minExperienceYears = number("minExperienceYears", fallback.minExperienceYears).toInt()
                    )
                }
                onResult(Result.success(PolicySet(
                    domestic = read("domestic", PolicyStore.defaults(false)),
                    international = read("international", PolicyStore.defaults(true))
                )))
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun savePolicies(domestic: Policy, international: Policy, onDone: (Result<Unit>) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onDone(Result.failure(IllegalStateException("Not signed in")))
            return
        }
        val values = mapOf(
            "policies" to mapOf(
                "domestic" to mapOf(
                    "maxDutyHours" to domestic.maxDutyHours,
                    "minRestHours" to domestic.minRestHours,
                    "minExperienceYears" to domestic.minExperienceYears
                ),
                "international" to mapOf(
                    "maxDutyHours" to international.maxDutyHours,
                    "minRestHours" to international.minRestHours,
                    "minExperienceYears" to international.minExperienceYears
                )
            ),
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedBy" to uid
        )
        db.collection("settings").document("policies").set(values, SetOptions.merge())
            .addOnSuccessListener { onDone(Result.success(Unit)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }

    fun loadRole(
        uid: String,
        onResult: (Role?) -> Unit
    ) {
        db.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                val active = doc.getBoolean("active") == true

                val role = doc.getString("role")
                    ?.let { storedRole ->
                        runCatching {
                            Role.valueOf(storedRole)
                        }.getOrNull()
                    }

                onResult(
                    if (active) role else null
                )
            }
            .addOnFailureListener {
                onResult(null)
            }
    }


    fun saveAvailability(
        uid: String, standby: Boolean, extraDuty: Boolean, swaps: Boolean,
        onDone: (Boolean) -> Unit
    ) {
        val data = mapOf(
            "standby" to standby,
            "extraDuty" to extraDuty,
            "swaps" to swaps,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        db.collection("availability").document(uid).set(data)
            .addOnCompleteListener { onDone(it.isSuccessful) }
    }


    fun loadAvailability(uid: String, onResult: (Result<Map<String, Any>?>) -> Unit) {
        db.collection("availability").document(uid).get()
            .addOnSuccessListener { onResult(Result.success(it.data)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }


    fun observeDuties(
        uid: String,
        onError: (String) -> Unit = {},
        onChange: (List<RosterItem.Duty>) -> Unit
    ): ListenerRegistration =
        db.collection("users").document(uid).collection("duties")
            .orderBy("start", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    onError(error.message ?: "Could not load duties")
                    return@addSnapshotListener
                }
                val duties = snap?.documents?.map { d ->
                    val type = runCatching { DutyType.valueOf(d.getString("type") ?: "") }
                        .getOrDefault(DutyType.FLIGHT)
                    RosterItem.Duty(
                        type = type,
                        title = d.getString("title") ?: "",
                        time = d.getString("time") ?: "",
                        flightInfo = d.getString("flightInfo") ?: "",
                        start = d.getTimestamp("start"),
                        end = d.getTimestamp("end")
                    )
                }.orEmpty()
                onChange(duties)
            }
}
