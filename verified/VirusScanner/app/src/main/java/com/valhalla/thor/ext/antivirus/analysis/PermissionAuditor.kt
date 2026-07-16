package com.valhalla.thor.ext.antivirus.analysis

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class PermissionAuditResult(
    val hasAccessibilityAbuse: Boolean,
    val hasOverlayAbuse: Boolean,
    val hasPersistenceAbuse: Boolean,
    val score: Int
)

class PermissionAuditor(private val context: Context) {

    /**
     * Audit app permissions. Legitimate apps are excluded from false positives by avoiding
     * standalone permission flags. Cumulative risky combinations of features trigger warnings.
     */
    fun auditPermissionProfile(packageName: String): PermissionAuditResult {
        val pm = context.packageManager
        var riskScore = 0
        var accessAbuse = false
        var overlayAbuse = false
        var persistAbuse = false
        var hasQueryAll = false

        try {
            // Bypass auditing of standard platform apps or packages signed with system signatures to eliminate false positives
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || 
                              (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystemApp) {
                return PermissionAuditResult(false, false, false, 0)
            }

            val info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val requestedPermissions = info.requestedPermissions ?: emptyArray()

            if (requestedPermissions.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")) {
                accessAbuse = true
            }
            if (requestedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
                overlayAbuse = true
            }
            if (requestedPermissions.contains("android.permission.RECEIVE_BOOT_COMPLETED")) {
                persistAbuse = true
            }
            if (requestedPermissions.contains("android.permission.QUERY_ALL_PACKAGES")) {
                hasQueryAll = true
            }

            // High-grade heuristics combo evaluation:
            // Standalone sensitive permissions are considered perfectly clean (0 points)
            // or very minor.
            // A combination of overlay + accessibility is highly dangerous (capturing overlays/clicks).
            // A combination of boot + accessibility + overlay is extremely typical of malware/trojans.
            
            val activeSensitivesCount = listOf(accessAbuse, overlayAbuse, persistAbuse).count { it }
            
            riskScore = when {
                // All three: Highly Malicious (overlay, accessibility, and boot receiver)
                accessAbuse && overlayAbuse && persistAbuse -> 85
                
                // Overlay + Accessibility: Highly suspicious (overlay hijacking + clickjacking)
                accessAbuse && overlayAbuse -> 65
                
                // Accessibility + Boot: Suspicious (persistent automation)
                accessAbuse && persistAbuse -> 45
                
                // Overlay + Boot: Low Suspicious (drawing persistently)
                overlayAbuse && persistAbuse -> 35
                
                // Single sensitive feature with excessive querying
                activeSensitivesCount == 1 && hasQueryAll -> 20
                
                // Single sensitive feature alone is completely clean!
                else -> 0
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return PermissionAuditResult(
            hasAccessibilityAbuse = accessAbuse,
            hasOverlayAbuse = overlayAbuse,
            hasPersistenceAbuse = persistAbuse,
            score = riskScore
        )
    }
}
