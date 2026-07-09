package com.valhalla.thor.ext.automation

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Calls Thor's ExtensionOpsProvider to perform a privileged package op. Runs a synchronous IPC — call
 * OFF the main thread. Tries each known Thor package (release, then debug) and uses the first that
 * resolves. Returns true when Thor reports the op succeeded.
 */
object ThorOps {
    // Must match Thor's ExtensionOpsProvider contract.
    private const val AUTHORITY_SUFFIX = ".extensionops"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_OK = "ok"

    fun run(context: Context, action: String, packages: List<String>): Boolean {
        if (packages.isEmpty()) return false
        val extras = Bundle().apply { putStringArray(KEY_PACKAGES, packages.toTypedArray()) }
        for (thorPkg in Config.THOR_PACKAGES) {
            val ok = runCatching {
                context.contentResolver.call(Uri.parse("content://$thorPkg$AUTHORITY_SUFFIX"), action, null, extras)?.getBoolean(KEY_OK)
            }.getOrNull()
            if (ok != null) return ok // provider resolved (this is the live Thor); use its verdict
        }
        return false // no Thor ops provider resolved
    }
}
