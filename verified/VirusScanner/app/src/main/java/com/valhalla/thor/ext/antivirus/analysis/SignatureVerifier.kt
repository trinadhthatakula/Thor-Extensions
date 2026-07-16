package com.valhalla.thor.ext.antivirus.analysis

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class SignatureVerifier(private val context: Context) {

    companion object {
        // Predefined Pins of original popular packages to immediately flags trojan clones.
        private val DEVELOPER_PINS = mapOf(
            "com.example.bankapp" to "A9:4B:C3:D2:12:34:56:78:90:AB:CD:EF:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12"
        )
    }

    /**
     * Audits the signing authority. Returns true if trusted, or if package is not pinned.
     * Returns false if signature discrepancy is found (highly indicative of a Trojan clone).
     */
    fun verifyDeveloperSignaturePin(packageName: String): Boolean {
        val pinnedHash = DEVELOPER_PINS[packageName] ?: return true // Neutral: not audited
        val actualHash = getSigningCertSha256(packageName) ?: return false
        
        return pinnedHash.equals(actualHash, ignoreCase = true)
    }

    fun getSigningCertSha256(packageName: String): String? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return null
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                if (signers.isNotEmpty()) computeSha256Fingerprint(signers[0].toByteArray()) else null
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                val signatures = packageInfo.signatures
                if (!signatures.isNullOrEmpty()) computeSha256Fingerprint(signatures[0].toByteArray()) else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun computeSha256Fingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(bytes)
        return hashed.joinToString(":") { String.format("%02X", it) }
    }
}
