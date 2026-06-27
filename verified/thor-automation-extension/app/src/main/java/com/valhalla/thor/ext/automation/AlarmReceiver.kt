package com.valhalla.thor.ext.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "toggle"
        val clusterName = intent.getStringExtra("cluster_name") ?: return

        val forwardIntent = Intent("com.valhalla.thor.action.TRIGGER_EXTENSION").apply {
            setPackage("com.valhalla.thor")
            putExtra("extension_class", "com.valhalla.thor.ext.automation.AutomationCluster")
            putExtra("trigger_id", "$action:$clusterName")
        }

        context.sendBroadcast(forwardIntent, "com.valhalla.thor.permission.TRIGGER_EXTENSION")
    }
}
