package com.valhalla.thor.ext.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

object Config {
    const val PREFS = "automation_prefs"

    /**
     * Cluster-list JSON (a serialized `List<AppCluster>`) — the single source of truth for clusters.
     * Each cluster's `isScheduled` flag rides inside this JSON, so no separate per-cluster provider
     * flag is needed.
     */
    const val KEY_SAVED_CLUSTERS = "saved_clusters"

    const val AUTHORITY = "com.valhalla.thor.ext.automation.config"

    // Thor's applicationId differs by build (…​.debug in debug); callers may try both.
    val THOR_PACKAGES = listOf("com.valhalla.thor", "com.valhalla.thor.debug")
}

/**
 * Config store for the automation extension. The config UI ([ConfigActivity]) and [AlarmReceiver] —
 * both in this extension's OWN process — write and read the cluster list here directly. The provider is
 * also exported so Thor can reach it via `call`. Backed by PRIVATE prefs — that exported IPC is the only
 * cross-process reach.
 *
 * Mirrors Strombringer's `StrombringerConfigProvider`, adapted for a String payload (the cluster JSON)
 * instead of the boolean/int flags Strombringer stores.
 */
class AutomationConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        // Security: the provider is exported so Thor and this extension's own config UI can reach it —
        // but NOT arbitrary apps.
        // getCallingPackage() is attested by the system against the caller's UID, so it's a
        // trustworthy allowlist gate. A null caller means a same-process call (our own config UI).
        val caller = callingPackage
        if (caller != null && caller != ctx.packageName && caller !in Config.THOR_PACKAGES) {
            throw SecurityException("Unauthorized caller: $caller")
        }
        val prefs = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val out = Bundle()
        // The key is the `arg`; defaults to the cluster-list key so a null-arg caller keeps working.
        val key = arg ?: Config.KEY_SAVED_CLUSTERS
        when (method) {
            // Only write when the caller actually supplied "value" — a missing key would clobber the
            // saved cluster list with null.
            "setString" -> if (extras?.containsKey("value") == true) {
                prefs.edit().putString(key, extras.getString("value")).apply()
            }
            "getString" -> out.putString("value", prefs.getString(key, null))
        }
        return out
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
