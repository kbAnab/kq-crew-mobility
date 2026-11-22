

package com.example.kenyaairways.auth

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kenyaairways.MainActivity
import com.example.kenyaairways.Session
import com.example.kenyaairways.User
import com.example.kenyaairways.data.UserRepository
import com.example.kenyaairways.databinding.FragmentLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

class LoginFragment : Fragment() {

    private var _b: FragmentLoginBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentLoginBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val auth = FirebaseAuth.getInstance()

        // Check whether a user is already signed in.
        auth.currentUser?.let {
            loadUserProfile(it)
            return
        }

        b.tvSignUp.setOnClickListener {
            startActivity(
                Intent(requireContext(), SignUpActivity::class.java)
            )
        }

        b.btnLogin.setOnClickListener {

            val email = b.etEmail.text.toString().trim()
            val password = b.etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                toast("Email and password are required.")
                return@setOnClickListener
            }

            b.btnLogin.isEnabled = false

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->

                    if (!isAdded || _b == null) return@addOnCompleteListener

                    if (task.isSuccessful) {
                        val user = auth.currentUser

                        if (user != null) {
                            loadUserProfile(user)
                        } else {
                            b.btnLogin.isEnabled = true
                            toast("Unable to retrieve user information.")
                        }

                    } else {
                        b.btnLogin.isEnabled = true
                        toast(
                            task.exception?.localizedMessage
                                ?: "Login failed."
                        )
                    }
                }
        }
    }

    private fun loadUserProfile(firebaseUser: FirebaseUser) {

        UserRepository.loadProfile(firebaseUser.uid) { result ->

            if (!isAdded || _b == null) return@loadProfile

            val profile = result.getOrNull()
            if (profile != null) {

                // Create the local app session using Firestore data.
                Session.user = User(
                    name = profile.name,
                    email = profile.email,
                    role = profile.role
                )

                (activity as? MainActivity)?.showHome()

            } else {

                FirebaseAuth.getInstance().signOut()

                b.btnLogin.isEnabled = true

                toast(if (result.isFailure) {
                    "Could not load your profile. Check your connection and try again."
                } else {
                    "Your profile is missing, inactive, or has an invalid role. Contact an administrator."
                })
            }
        }
    }

    private fun toast(message: String) {
        context?.let {
            Toast.makeText(
                it,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
