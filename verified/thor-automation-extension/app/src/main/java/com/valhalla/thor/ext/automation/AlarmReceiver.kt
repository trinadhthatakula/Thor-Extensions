package com.valhalla.thor.ext.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json

/**
 * Fires on the scheduled alarm. Resolves the cluster's packages from this extension's own prefs and
 * asks Thor's ExtensionOpsProvider to perform the op (which cold-starts Thor if needed). No broadcast.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "toggle"   // freeze | unfreeze | toggle
        val clusterName = intent.getStringExtra("cluster_name") ?: return

        val json = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
            .getString(Config.KEY_SAVED_CLUSTERS, null) ?: return
        val clusters = runCatching { Json.decodeFromString<List<AppCluster>>(json) }.getOrDefault(emptyList())
        val packages = clusters.firstOrNull { it.name == clusterName }?.packages ?: return
        if (packages.isEmpty()) return

        // BroadcastReceiver.onReceive is on the main thread; the ops call is a synchronous IPC, so hop
        // off it. goAsync keeps the receiver alive while the (fast, local) provider call runs.
        val pending = goAsync()
        Thread {
            try { ThorOps.run(context, action, packages) } finally { pending.finish() }
        }.start()
    }
}
