
package com.example.kenyaairways.auth

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.kenyaairways.data.UserRepository
import com.example.kenyaairways.databinding.ActivitySignUpBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.userProfileChangeRequest

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCreate.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString()
            val confirm = binding.etConfirm.text.toString()
            val requestedRole = when (binding.rgRole.checkedRadioButtonId) {
                com.example.kenyaairways.R.id.rbScheduler -> com.example.kenyaairways.Role.CREW_CONTROLLER
                com.example.kenyaairways.R.id.rbManager -> com.example.kenyaairways.Role.MANAGER
                com.example.kenyaairways.R.id.rbAdmin -> com.example.kenyaairways.Role.ADMIN
                else -> com.example.kenyaairways.Role.FLIGHT_CREW
            }

            when {
                name.isEmpty() || email.isEmpty() ||
                        pass.isEmpty() || confirm.isEmpty() ->
                    toast("Empty fields are not allowed.")

                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    toast("Enter a valid email address.")

                pass.length < 6 ->
                    toast("Password must be at least 6 characters.")

                pass != confirm ->
                    toast("Passwords do not match.")

                else -> createAccount(name, email, pass, requestedRole)
            }
        }
    }

    private fun createAccount(name: String, email: String, pass: String, requestedRole: com.example.kenyaairways.Role) {
        binding.btnCreate.isEnabled = false

        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->

                if (!task.isSuccessful) {
                    binding.btnCreate.isEnabled = true
                    toast(task.exception?.localizedMessage ?: "Sign up failed.")
                    return@addOnCompleteListener
                }

                val user = auth.currentUser

                if (user == null) {
                    binding.btnCreate.isEnabled = true
                    toast("Account created, but user information could not be loaded.")
                    return@addOnCompleteListener
                }

                // Update Firebase Authentication display name.
                val profileUpdates = userProfileChangeRequest {
                    displayName = name
                }

                user.updateProfile(profileUpdates)
                    .addOnCompleteListener {

                        // Create the corresponding Firestore user document.
                        UserRepository.createProfile(
                            uid = user.uid,
                            name = name,
                            email = user.email ?: email,
                            requestedRole = requestedRole
                        ) { result ->

                            if (result.isSuccess) {
                                auth.signOut()

                                toast(if (requestedRole == com.example.kenyaairways.Role.FLIGHT_CREW) {
                                    "Account created successfully. Please log in."
                                } else {
                                    "Account created as Flight Crew. Your ${requestedRole.label} request needs administrator approval."
                                })
                                finish()
                            } else {
                                // Keep the Auth account so a transient Firestore failure cannot
                                // destroy a valid registration. It remains unable to enter the app
                                // until its profile is repaired by an administrator.
                                auth.signOut()
                                toast("Authentication succeeded, but profile creation failed: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}. Contact an administrator before retrying.")
                                binding.btnCreate.isEnabled = true
                            }
                        }
                    }
            }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
