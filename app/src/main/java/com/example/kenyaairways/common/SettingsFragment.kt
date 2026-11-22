package com.example.kenyaairways.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.databinding.FragmentSettingsBinding
import com.google.firebase.auth.FirebaseAuth
import com.example.kenyaairways.notifications.PushNotifications

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false)
        return b.root
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, s: Bundle?) {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

        b.swPush.isChecked = PushNotifications.isEnabled(requireContext())
        b.swReminder.isChecked = prefs.getBoolean("duty_reminder", true)
        b.swPush.setOnCheckedChangeListener { _, on ->
            if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                !PushNotifications.canPostNotifications(requireContext())
            ) {
                (activity as? MainActivity)?.requestNotificationPermission()
            } else {
                PushNotifications.setEnabled(requireContext(), on)
            }
        }
        b.swReminder.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("duty_reminder", on).apply() }

        val email = FirebaseAuth.getInstance().currentUser?.email
        b.tvAccount.text = email ?: "Not signed in"
        b.btnReset.isEnabled = email != null
        b.btnReset.setOnClickListener {
            if (email == null) return@setOnClickListener
            FirebaseAuth.getInstance().sendPasswordResetEmail(email).addOnCompleteListener { task ->
                val msg = if (task.isSuccessful) "Reset email sent to $email"
                else task.exception?.localizedMessage ?: "Could not send the email"
                context?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
            }
        }

        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        b.tvVersion.text = "Version ${info.versionName}"
    }

    override fun onResume() { super.onResume(); (activity as? MainActivity)?.setScreenTitle("Settings") }

    fun reflectPushPermissionResult(granted: Boolean) {
        if (_b != null) b.swPush.isChecked = granted
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
