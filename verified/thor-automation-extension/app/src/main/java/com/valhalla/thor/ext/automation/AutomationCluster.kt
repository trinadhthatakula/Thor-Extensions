package com.valhalla.thor.ext.automation

import android.content.Context
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.extension.api.ShellExecutor

class AutomationCluster : AutomationExtension {
    override val name: String = "Thor Cluster Automator"
    override val description: String = "Automate freezing and unfreezing of custom app clusters."
    override val version: String = "1.0.0"
    override val author: String = "Thor Team"

    override fun onTrigger(context: Context, eventType: String, shellExecutor: ShellExecutor) {
        val parts = eventType.split(":")
        if (parts.size < 2) return
        val action = parts[0]
        val clusterName = parts[1]

        val extContext = try {
            context.createPackageContext("com.valhalla.thor.ext.automation", Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val prefs = extContext.getSharedPreferences("cluster_prefs", Context.MODE_PRIVATE)
        val packagesString = prefs.getString("cluster:$clusterName", "") ?: ""
        if (packagesString.isEmpty()) return

        val packageList = packagesString.split(",")

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
}
