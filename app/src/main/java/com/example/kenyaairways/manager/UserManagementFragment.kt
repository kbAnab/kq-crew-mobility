package com.example.kenyaairways.manager

import android.view.View
import android.widget.Toast
import com.example.kenyaairways.*
import com.example.kenyaairways.data.ManagedUserProfile
import com.example.kenyaairways.data.UserRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth

/** Administrator view over real user profiles; role changes are also enforced by Firestore rules. */
class UserManagementFragment : ListFragment("User Management") {

    private lateinit var adapter: RowAdapter<ManagedUserProfile>
    private var filter = 0
    private var users: List<ManagedUserProfile> = emptyList()

    private fun roleColor(role: Role) = when (role) {
        Role.ADMIN, Role.MANAGER -> R.color.primary
        Role.CREW_CONTROLLER -> R.color.duty_reserve
        Role.FLIGHT_CREW -> R.color.duty_flight
    }

    override fun onReady() {
        adapter = RowAdapter(emptyList()) { row, user ->
            val requested = user.requestedRole
            val signedInUser = FirebaseAuth.getInstance().currentUser?.uid == user.uid
            row.setTexts(
                user.name.ifBlank { user.email },
                user.email,
                if (requested != null) "Requested role: ${requested.label}" else "Account: ${if (user.active) "active" else "inactive"}"
            )
            row.badge(user.role.label, roleColor(user.role))
            row.accent(if (user.active) R.color.duty_off else R.color.divider)
            row.switchRow.visibility = View.VISIBLE
            row.switchRow.text = "App access"
            row.switchRow.setOnCheckedChangeListener(null)
            row.switchRow.isChecked = user.active
            row.switchRow.isEnabled = !signedInUser
            row.switchRow.setOnCheckedChangeListener { button, active ->
                button.isEnabled = false
                UserRepository.updateManagedUser(user.uid, user.role, active) { result ->
                    if (!isViewReady) return@updateManagedUser
                    if (result.isFailure) {
                        Toast.makeText(requireContext(), "Could not update access: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
            row.actions.visibility = View.VISIBLE
            row.btnPrimary.text = when {
                signedInUser -> "Signed-in admin account"
                requested == null -> "Change role"
                else -> "Approve requested role"
            }
            row.btnPrimary.isEnabled = !signedInUser
            row.btnPrimary.setOnClickListener {
                val choices = if (requested != null) listOf(requested) else Role.entries
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (requested == null) "Assign role" else "Approve role request")
                    .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                        UserRepository.updateManagedUser(
                            user.uid, choices[index], user.active, clearRoleRequest = true
                        ) { result ->
                            if (!isViewReady) return@updateManagedUser
                            result.exceptionOrNull()?.let {
                                Toast.makeText(requireContext(), "Role update failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                            refresh()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        b.rvList.adapter = adapter
        b.fabAdd.visibility = View.GONE
        setupChips(listOf("All") + Role.entries.map { it.label }) { filter = it; render() }
        refresh()
    }

    private fun refresh() {
        setSummary("Loading user profiles…")
        UserRepository.loadManagedUsers { result ->
            if (!isViewReady) return@loadManagedUsers
            result.onSuccess {
                users = it
                render()
            }.onFailure {
                users = emptyList()
                adapter.submit(emptyList())
                showEmpty(true, "Could not load users. Confirm administrator access and Firestore rules.")
                setSummary("User data unavailable")
            }
        }
    }

    private fun render() {
        val filtered = if (filter == 0) users else users.filter { it.role == Role.entries[filter - 1] }
        adapter.submit(filtered)
        showEmpty(filtered.isEmpty(), "No users in this group")
        val pending = users.count { it.requestedRole != null }
        setSummary("${users.count { it.active }} active · ${users.size} accounts · $pending role requests awaiting review")
    }
}
