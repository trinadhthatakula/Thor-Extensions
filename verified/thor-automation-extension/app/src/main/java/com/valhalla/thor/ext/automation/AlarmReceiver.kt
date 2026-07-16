package com.valhalla.thor.ext.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * Fires on the scheduled alarm. Resolves the cluster's packages from this extension's own prefs and
 * asks Thor's ExtensionOpsProvider to perform the op (which cold-starts Thor if needed). No broadcast.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "toggle"   // freeze | unfreeze | toggle
        val clusterName = intent.getStringExtra("cluster_name") ?: return
        Log.d("AlarmReceiver", "onReceive triggered: action=$action, clusterName=$clusterName")

        // BroadcastReceiver.onReceive is on the main thread. Move SharedPreferences disk read, JSON parsing,
        // and the synchronous ContentProvider IPC off the main thread to prevent UI jank / ANRs.
        // goAsync keeps the receiver alive while the background thread runs.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
                    .getString(Config.KEY_SAVED_CLUSTERS, null) ?: run {
                        Log.w("AlarmReceiver", "No saved clusters found in SharedPreferences")
                        return@launch
                    }
                val clusters = runCatching { Json.decodeFromString<List<AppCluster>>(json) }.getOrDefault(emptyList())
                val cluster = clusters.firstOrNull { it.name == clusterName } ?: run {
                    Log.w("AlarmReceiver", "Cluster not found: $clusterName")
                    return@launch
                }
                val packages = cluster.packages
                if (packages.isEmpty()) {
                    Log.w("AlarmReceiver", "Cluster packages list is empty for $clusterName")
                    return@launch
                }

                Log.d("AlarmReceiver", "Executing operation '$action' for packages: $packages")
                val success = ThorOps.run(context, action, packages)
                Log.d("AlarmReceiver", "Operation '$action' completed with success=$success")

                // Reschedule for tomorrow at the same time
                val hour = if (action == "freeze") cluster.freezeHour else cluster.unfreezeHour
                val minute = if (action == "freeze") cluster.freezeMinute else cluster.unfreezeMinute
                Log.d("AlarmReceiver", "Rescheduling alarm for action=$action at tomorrow $hour:$minute")
                scheduleAlarm(context, clusterName, action, hour, minute)
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Error executing scheduled cluster operation", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun scheduleAlarm(context: Context, clusterName: String, action: String, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("cluster_name", clusterName)
                putExtra("action", action)
            }

            val requestCode = if (action == "freeze") {
                clusterName.hashCode() * 31 + 1
            } else {
                clusterName.hashCode() * 31 + 2
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        }

        fun cancelAlarm(context: Context, clusterName: String, action: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("cluster_name", clusterName)
                putExtra("action", action)
            }

            val requestCode = if (action == "freeze") {
                clusterName.hashCode() * 31 + 1
            } else {
                clusterName.hashCode() * 31 + 2
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }

        fun cancelAllAlarms(context: Context, clusterName: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // 1. Cancel legacy freeze alarm (request code clusterName.hashCode())
            val legacyIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("cluster_name", clusterName)
                putExtra("action", "freeze")
            }
            val pendingLegacy = PendingIntent.getBroadcast(
                context,
                clusterName.hashCode(),
                legacyIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingLegacy != null) {
                alarmManager.cancel(pendingLegacy)
                pendingLegacy.cancel()
            }

            // 2. Cancel new freeze alarm
            cancelAlarm(context, clusterName, "freeze")

            // 3. Cancel new unfreeze alarm
            cancelAlarm(context, clusterName, "unfreeze")
        }
    }
}
