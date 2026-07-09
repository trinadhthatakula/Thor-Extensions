@file:Suppress("unused")

package com.valhalla.thor.ext.automation

import com.valhalla.thor.extension.api.ThorExtension
import kotlinx.serialization.Serializable

/**
 * A named group of app packages frozen/unfrozen together. Persisted as JSON by the config UI and read
 * back by the config UI + AlarmReceiver (both in THIS process); the actual freeze runs in Thor via the
 * ExtensionOpsProvider (see [ThorOps]).
 */
@Serializable
data class AppCluster(
    val name: String,
    val packages: List<String>,
    val isScheduled: Boolean = false
)

/**
 * Metadata surface Thor loads (by `thor.extension.class`) to list this extension + offer Configure.
 * It NO LONGER implements AutomationExtension/onTrigger: Thor never runs extension code now — the
 * extension calls Thor's ExtensionOpsProvider instead (see the 2026-07-09 ops-provider design). Kept as
 * a plain ThorExtension (metadata only), like StrombringerExtension.
 */
@Suppress("unused")
class AutomationCluster : ThorExtension {
    override val name: String = "Thor Cluster Automator"
    override val description: String = "Automate freezing and unfreezing of custom app clusters."
    override val version: String = "1.0.0"
    override val author: String = "Thor Team"
}
