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
 * Config store for the automation extension. The config UI ([ConfigActivity], which runs in this
 * extension's OWN process) writes the cluster list here; [AutomationCluster.onTrigger] (which runs in
 * Thor's process) reads it back via this exported provider's `call`. Backed by PRIVATE prefs — the
 * exported provider IPC is the only cross-process reach.
 *
 * Mirrors Strombringer's `StrombringerConfigProvider`, adapted for a String payload (the cluster JSON)
 * instead of the boolean/int flags Strombringer stores.
 */
class AutomationConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        val prefs = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val out = Bundle()
        // The key is the `arg`; defaults to the cluster-list key so a null-arg caller keeps working.
        val key = arg ?: Config.KEY_SAVED_CLUSTERS
        when (method) {
            "setString" -> prefs.edit().putString(key, extras?.getString("value")).apply()
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
