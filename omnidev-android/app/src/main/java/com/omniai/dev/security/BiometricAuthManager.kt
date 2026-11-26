package com.omniai.dev.security

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

sealed class BiometricAvailability {
    data object Ready : BiometricAvailability()
    data object NotEnrolled : BiometricAvailability()
    data class Unavailable(val reason: String) : BiometricAvailability()
}

object BiometricAuthManager {

    private fun allowedAuthenticators(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    fun checkAvailability(context: Context): BiometricAvailability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(allowedAuthenticators())) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Ready
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricAvailability.Unavailable("No biometric or secure lock hardware detected.")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricAvailability.Unavailable("Biometric hardware is currently unavailable.")
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.Unavailable("A device security update is required.")
            else -> BiometricAvailability.Unavailable("Device authentication is unavailable.")
        }
    }

    fun authenticate(
        context: Context,
        title: String = "Unlock OmniDev Vault",
        subtitle: String = "Verify fingerprint or device lock to decrypt GitHub & AI keys",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            onError("Unable to attach BiometricPrompt to host activity.")
            return
        }

        when (val availability = checkAvailability(activity)) {
            is BiometricAvailability.NotEnrolled -> {
                onError("Set up a fingerprint or screen lock in Android Settings to unlock the Vault.")
                return
            }
            is BiometricAvailability.Unavailable -> {
                onError(availability.reason)
                return
            }
            BiometricAvailability.Ready -> Unit
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Authentication not recognized. Try again.")
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowedAuthenticators())
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(promptInfo)
    }

    fun openSecuritySettings(context: Context) {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
}
