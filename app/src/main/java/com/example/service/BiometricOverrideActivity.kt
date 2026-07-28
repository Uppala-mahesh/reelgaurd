package com.example.service

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.ReelGuardApplication
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Transparent bridge activity that shows the biometric prompt and, on
 * success, performs the emergency override + dismisses the cooldown
 * overlay owned by [ReelService].
 */
class BiometricOverrideActivity : FragmentActivity() {

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("package_name") ?: ""
        if (packageName.isEmpty() || !ReelGuardApplication.isInitialized()) {
            finish()
            return
        }

        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Override cancelled: $errString", Toast.LENGTH_SHORT).show()
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(applicationContext, "Override authorized.", Toast.LENGTH_SHORT).show()

                    val repo = ReelGuardApplication.instance.repository
                    lifecycleScope.launch {
                        repo.emergencyOverride(packageName, "Biometric emergency override.")

                        // Ask the running service to tear down the lock overlay.
                        val svcIntent = Intent(this@BiometricOverrideActivity, ReelService::class.java)
                            .putExtra("remove_overlay", true)
                        startService(svcIntent)

                        finish()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Not recognized — cooldown stays active.", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock the feed early?")
            .setSubtitle("Confirm your identity to override the ReelGuard cooldown.")
            .setNegativeButtonText("Keep cooldown")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
