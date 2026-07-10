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

        // BroadcastReceiver.onReceive is on the main thread. Move SharedPreferences disk read, JSON parsing,
        // and the synchronous ContentProvider IPC off the main thread to prevent UI jank / ANRs.
        // goAsync keeps the receiver alive while the background thread runs.
        val pending = goAsync()
        Thread {
            try {
                val json = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
                    .getString(Config.KEY_SAVED_CLUSTERS, null) ?: return@Thread
                val clusters = runCatching { Json.decodeFromString<List<AppCluster>>(json) }.getOrDefault(emptyList())
                val packages = clusters.firstOrNull { it.name == clusterName }?.packages ?: return@Thread
                if (packages.isEmpty()) return@Thread

                ThorOps.run(context, action, packages)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
