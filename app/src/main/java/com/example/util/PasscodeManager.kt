package com.example.util

import java.security.MessageDigest

/**
 * Utility for secure hashing and verification of app passcodes.
 * Uses SHA-256 with an internal application salt.
 */
object PasscodeManager {
    private const val SALT = "GOONY_VAULT_PASSCODE_SALT_v1"

    fun hashPasscode(code: String): String {
        val input = "$SALT:$code"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPasscode(enteredCode: String, storedHash: String): Boolean {
        if (enteredCode.isBlank() || storedHash.isBlank()) return false
        val computedHash = hashPasscode(enteredCode)
        return computedHash.equals(storedHash, ignoreCase = true)
    }
}
