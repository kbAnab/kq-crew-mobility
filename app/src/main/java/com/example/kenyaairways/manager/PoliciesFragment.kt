package com.example.kenyaairways.manager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.Policy
import com.example.kenyaairways.PolicyStore
import com.example.kenyaairways.databinding.FragmentPoliciesBinding
import com.example.kenyaairways.data.UserRepository
import com.google.android.material.textfield.TextInputEditText

class PoliciesFragment : Fragment() {

    private var _b: FragmentPoliciesBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPoliciesBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        show()
        UserRepository.loadPolicies { result ->
            if (!isAdded || _b == null) return@loadPolicies
            result.onSuccess { policies ->
                PolicyStore.save(requireContext(), false, policies.domestic)
                PolicyStore.save(requireContext(), true, policies.international)
                show(policies.domestic, policies.international)
            }.onFailure {
                toast("Could not load shared policy settings: ${it.localizedMessage}")
            }
        }
        b.btnSave.setOnClickListener { save() }
        b.btnDefaults.setOnClickListener {
            savePolicies(PolicyStore.defaults(false), PolicyStore.defaults(true), "Defaults restored")
        }
    }

    private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    private fun show() {
        val dom = PolicyStore.load(requireContext(), false)
        val intl = PolicyStore.load(requireContext(), true)
        show(dom, intl)
    }

    private fun show(dom: Policy, intl: Policy) {
        b.etDomDuty.setText(fmt(dom.maxDutyHours)); b.etDomRest.setText(fmt(dom.minRestHours))
        b.etDomExp.setText(dom.minExperienceYears.toString())
        b.etIntDuty.setText(fmt(intl.maxDutyHours)); b.etIntRest.setText(fmt(intl.minRestHours))
        b.etIntExp.setText(intl.minExperienceYears.toString())
    }

    private fun number(field: TextInputEditText, min: Float, max: Float): Float? {
        val v = field.text.toString().toFloatOrNull()
        return if (v == null || v < min || v > max) {
            field.error = "Enter a value from ${fmt(min)} to ${fmt(max)}"; null
        } else v
    }

    private fun save() {
        val domesticBaseline = PolicyStore.defaults(false)
        val internationalBaseline = PolicyStore.defaults(true)
        val dd = number(b.etDomDuty, 1f, domesticBaseline.maxDutyHours)
        val dr = number(b.etDomRest, domesticBaseline.minRestHours, 24f)
        val de = number(b.etDomExp, domesticBaseline.minExperienceYears.toFloat(), 40f)
        val id = number(b.etIntDuty, 1f, internationalBaseline.maxDutyHours)
        val ir = number(b.etIntRest, internationalBaseline.minRestHours, 24f)
        val ie = number(b.etIntExp, internationalBaseline.minExperienceYears.toFloat(), 40f)
        if (dd == null || dr == null || de == null || id == null || ir == null || ie == null) return
        savePolicies(Policy(dd, dr, de.toInt()), Policy(id, ir, ie.toInt()), "Rules saved and shared with the recommendation service.")
    }

    private fun savePolicies(domestic: Policy, international: Policy, successMessage: String) {
        b.btnSave.isEnabled = false
        b.btnDefaults.isEnabled = false
        UserRepository.savePolicies(domestic, international) { result ->
            if (!isAdded || _b == null) return@savePolicies
            b.btnSave.isEnabled = true
            b.btnDefaults.isEnabled = true
            result.onSuccess {
                PolicyStore.save(requireContext(), false, domestic)
                PolicyStore.save(requireContext(), true, international)
                show(domestic, international)
                toast(successMessage)
            }.onFailure {
                toast("Could not save shared rules: ${it.localizedMessage}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); (activity as? MainActivity)?.setScreenTitle("Policies") }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
