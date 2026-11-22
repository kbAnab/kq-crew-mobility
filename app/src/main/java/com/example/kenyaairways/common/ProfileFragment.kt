package com.example.kenyaairways.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.Session
import com.example.kenyaairways.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {
    private var _b: FragmentProfileBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentProfileBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        Session.user?.let {
            b.tvName.text = it.name
            b.tvEmail.text = it.email
            b.tvRole.text = it.role.label
        }
        b.btnLogout.setOnClickListener { (requireActivity() as MainActivity).logout() }
    }

    override fun onResume() { super.onResume(); (activity as? MainActivity)?.setScreenTitle("Profile") }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
