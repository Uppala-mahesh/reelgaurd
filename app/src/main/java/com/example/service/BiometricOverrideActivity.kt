package com.example.service

import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.ReelGuardApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

class BiometricOverrideActivity : FragmentActivity() {

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity transparent/empty layout
        setContentView(android.R.layout.select_dialog_item)

        val packageName = intent.getStringExtra("package_name") ?: ""
        if (packageName.isEmpty()) {
            finish()
            return
        }

        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Authorization canceled: $errString", Toast.LENGTH_SHORT).show()
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(applicationContext, "Bypass Authorized!", Toast.LENGTH_SHORT).show()

                    val repo = ReelGuardApplication.instance.repository
                    CoroutineScope(Dispatchers.Main).launch {
                        // Insert override log & reset counters
                        repo.emergencyOverride(packageName, "Biometric emergency bypass activated.")
                        
                        // Clear service overlay
                        ReelService.start(this@BiometricOverrideActivity) // starts it, which triggers onStartCommand to remove overlays

                        // Dismiss overlay and session
                        val intent = android.content.Intent(this@BiometricOverrideActivity, ReelService::class.java).apply {
                            putExtra("remove_overlay", true)
                        }
                        startService(intent)

                        finish()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Identification failed! Cooldown remains active.", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ReelGuard Safety Limit Bypass")
            .setSubtitle("Biometric confirmation required to initiate emergency scrolling override.")
            .setNegativeButtonText("Keep Cooldown")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
