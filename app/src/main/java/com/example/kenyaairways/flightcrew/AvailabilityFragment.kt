
package com.example.kenyaairways.flightcrew

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.data.UserRepository
import com.example.kenyaairways.databinding.FragmentAvailabilityBinding
import com.google.firebase.auth.FirebaseAuth

class AvailabilityFragment : Fragment() {

    private var _b: FragmentAvailabilityBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentAvailabilityBinding.inflate(
            inflater,
            container,
            false
        )
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadAvailability()

        b.btnSave.setOnClickListener {
            saveAvailability()
        }
    }


    private fun loadAvailability() {

        val uid = FirebaseAuth.getInstance().currentUser?.uid

        if (uid == null) {
            toast("Please log in to view your availability.")
            return
        }

        UserRepository.loadAvailability(uid) { result ->

            if (!isAdded || _b == null) return@loadAvailability

            result.onSuccess { preferences ->
                if (preferences != null) {

                    b.swStandby.isChecked = preferences["standby"] as? Boolean ?: false

                    b.swExtra.isChecked = preferences["extraDuty"] as? Boolean ?: false

                    b.swSwap.isChecked = preferences["swaps"] as? Boolean ?: false

                } else {
                    b.swStandby.isChecked = false
                    b.swExtra.isChecked = false
                    b.swSwap.isChecked = false
                }
            }.onFailure {
                toast("Could not load availability: ${it.localizedMessage}")
            }
        }
    }

    /**
     * Save the selected preferences to Firestore.
     */
    private fun saveAvailability() {

        val uid = FirebaseAuth.getInstance().currentUser?.uid

        if (uid == null) {
            toast("Please log in before saving your availability.")
            return
        }

        val standby = b.swStandby.isChecked
        val extraDuty = b.swExtra.isChecked
        val swaps = b.swSwap.isChecked

        b.btnSave.isEnabled = false

        UserRepository.saveAvailability(
            uid = uid,
            standby = standby,
            extraDuty = extraDuty,
            swaps = swaps
        ) { success ->

            if (!isAdded || _b == null) return@saveAvailability

            b.btnSave.isEnabled = true

            if (success) {
                toast("Your availability preferences have been saved.")
            } else {
                toast("Unable to save preferences. Please try again.")
            }
        }
    }

    private fun toast(message: String) {
        context?.let {
            Toast.makeText(
                it,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setScreenTitle("Availability")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
