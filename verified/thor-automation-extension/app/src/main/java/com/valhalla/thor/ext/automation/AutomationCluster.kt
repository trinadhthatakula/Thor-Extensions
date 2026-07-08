@file:Suppress("unused")

package com.valhalla.thor.ext.automation

import android.content.Context
import android.net.Uri
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.extension.api.ExtensionDataStore
import com.valhalla.thor.extension.api.ShellExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A named group of app packages that can be frozen/unfrozen together. [isScheduled] rides inside this
 * model (rather than a separate provider flag) so the whole cluster list is a single JSON string.
 */
@Serializable
data class AppCluster(
    val name: String,
    val packages: List<String>,
    val isScheduled: Boolean = false
)

/**
 * Thor-side automation surface for app clusters. Thor loads THIS class into ITS OWN process (via
 * PathClassLoader) to read the metadata shown in the Extension Manager and to run [onTrigger] when an
 * alarm/shortcut/broadcast fires. It must therefore stay free of Compose/Asgard — anything that would
 * link against Thor's minified runtime is forbidden here (that coupling is exactly what broke the old
 * in-host `@Composable ConfigurationScreen`, removed from the api in 3.0.0).
 *
 * The configuration UI now lives in [ConfigActivity], which Thor launches by Intent
 * (action `com.valhalla.thor.extension.action.CONFIGURE`) so it runs in THIS app's OWN process with
 * its own full Compose/Asgard. That UI persists clusters through [AutomationConfigProvider]
 * (own-process private prefs), so [onTrigger] reads the cluster list back out via the same provider
 * IPC — the host [ExtensionDataStore] is no longer written by the out-of-process UI.
 */
@Suppress("unused")
class AutomationCluster : AutomationExtension {
    override val name: String = "Thor Cluster Automator"
    override val description: String = "Automate freezing and unfreezing of custom app clusters."
    override val version: String = "1.0.0"
    override val author: String = "Thor Team"

    override suspend fun onTrigger(
        context: Context,
        eventType: String,
        shellExecutor: ShellExecutor,
        dataStore: ExtensionDataStore
    ) {
        val parts = eventType.split(":")
        if (parts.size < 2) return
        val action = parts[0]
        val clusterName = parts[1]

        // Provider IPC is the source of truth (written by the out-of-process config UI). Fall back to
        // the host dataStore for any legacy install that still has clusters in Thor's Room.
        val clustersJson = readSavedClusters(context)
            ?: dataStore.getString(Config.KEY_SAVED_CLUSTERS)
            ?: return
        val clustersList = try {
            Json.decodeFromString<List<AppCluster>>(clustersJson)
        } catch (_: Exception) {
            emptyList()
        }
        val cluster = clustersList.firstOrNull { it.name == clusterName } ?: return
        val packageList = cluster.packages

        when (action) {
            "freeze" -> {
                for (pkg in packageList) {
                    shellExecutor.execute("pm disable-user --user 0 $pkg")
                }
            }
            "unfreeze" -> {
                for (pkg in packageList) {
                    shellExecutor.execute("pm enable $pkg")
                }
            }
            "toggle" -> {
                val firstPkg = packageList.firstOrNull() ?: return
                val statusResult = shellExecutor.execute("pm list packages -d $firstPkg")
                val isFrozen = statusResult.second?.contains(firstPkg) == true

                for (pkg in packageList) {
                    if (isFrozen) {
                        shellExecutor.execute("pm enable $pkg")
                    } else {
                        shellExecutor.execute("pm disable-user --user 0 $pkg")
                    }
                }
            }
        }
    }

    /**
     * Reads the persisted cluster-list JSON from [AutomationConfigProvider]. [onTrigger] runs in Thor's
     * process, but the config UI writes to the extension's OWN private prefs, so the exported provider
     * call is the only path that reaches those writes. Returns null on any failure so the caller can
     * fall back to the host dataStore.
     */
    private fun readSavedClusters(context: Context): String? = runCatching {
        val uri = Uri.parse("content://" + Config.AUTHORITY)
        context.contentResolver
            .call(uri, "getString", Config.KEY_SAVED_CLUSTERS, null)
            ?.getString("value")
    }.getOrNull()
}
