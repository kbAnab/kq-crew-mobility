package com.example.kenyaairways

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.kenyaairways.auth.LoginFragment
import com.example.kenyaairways.common.*
import com.example.kenyaairways.crewcontroller.*
import com.example.kenyaairways.databinding.ActivityMainBinding
import com.example.kenyaairways.databinding.FragmentPlaceholderBinding
import com.example.kenyaairways.flightcrew.*
import com.example.kenyaairways.manager.*
import com.google.firebase.auth.FirebaseAuth
import com.example.kenyaairways.notifications.PushNotifications
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("push", granted).apply()
        if (granted) PushNotifications.syncToken(this)
        (supportFragmentManager.findFragmentById(R.id.container) as? SettingsFragment)
            ?.reflectPushPermissionResult(granted)
    }

    // Represents one item in the bottom navigation.
    private class Dest(
        val title: String,
        val icon: Int,
        val create: () -> Fragment
    )

    private lateinit var binding: ActivityMainBinding

    private var destinations: List<Dest> = emptyList()

    companion object {
        private const val M_NOTIF = 100
        private const val M_PROFILE = 101
        private const val M_SETTINGS = 102
        private const val M_POLICIES = 103
        private const val M_USERS = 104
        private const val M_LOGOUT = 105
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The toolbar (AppBarLayout) and bottom nav handle their own insets.
        // When they are hidden (login screen) pad the container instead.
        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (binding.appBar.isVisible) v.setPadding(0, 0, 0, 0)
            else v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)

        // Update the toolbar back arrow when the back stack changes.
        supportFragmentManager.addOnBackStackChangedListener {
            supportActionBar?.setDisplayHomeAsUpEnabled(
                supportFragmentManager.backStackEntryCount > 0
            )
        }

        // Handle bottom navigation selections.
        binding.bottomNav.setOnItemSelectedListener { item ->
            val destination = destinations.getOrNull(item.itemId)

            if (destination != null) {
                show(destination)
                true
            } else {
                false
            }
        }

        // Display the correct screen based on the current session.
        val user = Session.user

        when {
            user == null -> showLogin()

            savedInstanceState == null -> showHome()

            else -> {
                buildNav(user.role)
                setChromeVisible(true)
            }
        }
    }

    // ----------------------------------------------------
    // Navigation methods used by Fragments
    // ----------------------------------------------------

    fun showLogin() {
        Session.user = null

        clearBackStack()

        destinations = emptyList()

        setChromeVisible(false)

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, LoginFragment())
            .commit()

        invalidateOptionsMenu()
    }

    /** Sign out of Firebase and return to the login screen. */
    fun logout() {
        PushNotifications.unregisterCurrentToken {
            FirebaseAuth.getInstance().signOut()
            showLogin()
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun showHome() {
        val user = Session.user

        if (user == null) {
            showLogin()
            return
        }

        clearBackStack()

        buildNav(user.role)

        setChromeVisible(true)

        // Select the first item in bottom navigation.
        binding.bottomNav.menu.getItem(0).isChecked = true

        show(destinations[0])

        if (PushNotifications.isEnabled(this)) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PushNotifications.syncToken(this)
            }
        }

        invalidateOptionsMenu()
    }

    // Open a screen on top of the current screen.
    // This adds the screen to the back stack.
    fun open(fragment: Fragment, title: String) {
        if (!canAccess(fragment)) {
            Toast.makeText(this, "Your account is not authorized to open this screen.", Toast.LENGTH_SHORT).show()
            return
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()

        setScreenTitle(title)
    }

    // Allow Fragments to change the toolbar title.
    fun setScreenTitle(title: String) {
        supportActionBar?.title = title
    }

    // ----------------------------------------------------
    // Internal navigation methods
    // ----------------------------------------------------

    private fun show(destination: Dest) {
        clearBackStack()

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, destination.create())
            .commit()

        setScreenTitle(destination.title)
    }

    private fun clearBackStack() {
        supportFragmentManager.popBackStackImmediate(
            null,
            FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
    }

    private fun setChromeVisible(visible: Boolean) {
        val visibility = if (visible) {
            View.VISIBLE
        } else {
            View.GONE
        }

        binding.appBar.visibility = visibility
        binding.bottomNav.visibility = visibility
        ViewCompat.requestApplyInsets(binding.container)
    }

    // ----------------------------------------------------
    // Role-based bottom navigation
    // ----------------------------------------------------

    private fun destinationsFor(role: Role): List<Dest> {
        return when (role) {

            Role.FLIGHT_CREW -> listOf(
                Dest(
                    "Dashboard",
                    R.drawable.ic_home
                ) {
                    DashboardFragment()
                },

                Dest(
                    "My Schedule",
                    R.drawable.ic_calendar
                ) {
                    MyScheduleFragment()
                },

                Dest(
                    "Availability",
                    R.drawable.ic_check
                ) {
                    AvailabilityFragment()
                }
            )

            Role.CREW_CONTROLLER -> listOf(
                Dest(
                    "Dashboard",
                    R.drawable.ic_home
                ) {
                    DashboardFragment()
                },

                Dest(
                    "Allocation",
                    R.drawable.ic_group
                ) {
                    CrewAllocationFragment()
                },

                Dest(
                    "AI Picks",
                    R.drawable.ic_flight
                ) {
                    AIRecommendationsFragment()
                },

                Dest(
                    "Approvals",
                    R.drawable.ic_check
                ) {
                    AssignmentApprovalFragment()
                },

                Dest(
                    "Alerts",
                    R.drawable.ic_warning
                ) {
                    AlertsFragment()
                }
            )

            Role.MANAGER -> listOf(
                Dest(
                    "Dashboard",
                    R.drawable.ic_home
                ) {
                    DashboardFragment()
                },

                Dest(
                    "Performance",
                    R.drawable.ic_chart
                ) {
                    PerformanceFragment()
                },

                Dest(
                    "Compliance",
                    R.drawable.ic_check
                ) {
                    ComplianceFragment()
                },

                Dest(
                    "Reports",
                    R.drawable.ic_book
                ) {
                    ReportsFragment()
                }
            )

            Role.ADMIN -> listOf(
                Dest("Dashboard", R.drawable.ic_home) { DashboardFragment() },
                Dest("User Management", R.drawable.ic_group) { UserManagementFragment() },
                Dest("Performance", R.drawable.ic_chart) { PerformanceFragment() },
                Dest("Compliance", R.drawable.ic_check) { ComplianceFragment() },
                Dest("Reports", R.drawable.ic_book) { ReportsFragment() }
            )
        }
    }

    private fun canAccess(fragment: Fragment): Boolean {
        val role = Session.user?.role ?: return false
        return when (fragment) {
            is CrewAllocationFragment, is AIRecommendationsFragment,
            is AssignmentApprovalFragment, is AlertsFragment -> role == Role.CREW_CONTROLLER
            is UserManagementFragment -> role == Role.ADMIN
            is PerformanceFragment, is ComplianceFragment, is ReportsFragment,
            is PoliciesFragment -> role == Role.MANAGER || role == Role.ADMIN
            is MyScheduleFragment, is AvailabilityFragment,
            is AssignmentDetailsFragment -> role == Role.FLIGHT_CREW
            else -> true
        }
    }

    private fun buildNav(role: Role) {
        destinations = destinationsFor(role)

        binding.bottomNav.menu.clear()

        destinations.forEachIndexed { index, destination ->

            binding.bottomNav.menu.add(
                Menu.NONE,
                index,
                index,
                destination.title
            ).setIcon(destination.icon)
        }
    }

    // ----------------------------------------------------
    // Toolbar overflow menu
    // ----------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()

        val user = Session.user ?: return false

        menu.add(
            Menu.NONE,
            M_NOTIF,
            0,
            "Notifications"
        )

        menu.add(
            Menu.NONE,
            M_PROFILE,
            1,
            "Profile"
        )

        menu.add(
            Menu.NONE,
            M_SETTINGS,
            2,
            "Settings"
        )

        if (user.role == Role.MANAGER || user.role == Role.ADMIN) {

            menu.add(
                Menu.NONE,
                M_POLICIES,
                3,
                "Policies"
            )

        }
        if (user.role == Role.ADMIN) menu.add(Menu.NONE, M_USERS, 4, "User Management")

        menu.add(
            Menu.NONE,
            M_LOGOUT,
            9,
            "Log out"
        )

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        val role = Session.user?.role ?: run { showLogin(); return true }
        if (item.itemId == M_USERS && role != Role.ADMIN) return true
        if (item.itemId == M_POLICIES && role != Role.MANAGER && role != Role.ADMIN) return true

        when (item.itemId) {

            M_NOTIF -> open(
                NotificationsFragment(),
                "Notifications"
            )

            M_PROFILE -> open(
                ProfileFragment(),
                "Profile"
            )

            M_SETTINGS -> open(
                SettingsFragment(),
                "Settings"
            )

            M_POLICIES -> open(
                PoliciesFragment(),
                "Policies"
            )

            M_USERS -> open(
                UserManagementFragment(),
                "User Management"
            )

            M_LOGOUT -> logout()

            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
            }

            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

// ========================================================
// Placeholder Fragment
// ========================================================

/*
 * A simple "coming soon" screen.
 *
 * Use this for screens that have not been implemented yet.
 * Replace it with the real UI when the screen is ready.
 */

abstract class PlaceholderFragment(
    private val screenTitle: String,
    private val description: String
) : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val binding = FragmentPlaceholderBinding.inflate(
            inflater,
            container,
            false
        )

        binding.tvTitle.text = screenTitle
        binding.tvDescription.text = description

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        (activity as? MainActivity)
            ?.setScreenTitle(screenTitle)
    }
}
