package com.valhalla.thor.ext.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.extension.api.ShellExecutor
import java.util.Calendar

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

    @Composable
    override fun ConfigurationScreen(shellExecutor: ShellExecutor) {
        val context = LocalContext.current
        var clusterName by remember { mutableStateOf("WorkApps") }
        var searchQuery by remember { mutableStateOf("") }
        val selectedPackages = remember { mutableStateListOf<String>() }

        val installedApps = remember {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .sortedBy { it.loadLabel(pm).toString() }
        }

        val filteredApps = remember(searchQuery) {
            val pm = context.packageManager
            installedApps.filter {
                it.loadLabel(pm).toString().contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }

        val extContext = remember {
            try {
                context.createPackageContext("com.valhalla.thor.ext.automation", Context.CONTEXT_IGNORE_SECURITY)
            } catch (e: Exception) {
                context
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "Thor Cluster Automator",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Create groups of apps to toggle, freeze, or schedule action execution securely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = clusterName,
                onValueChange = { clusterName = it },
                label = { Text("Cluster/Tag Name") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Installed Apps") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            )

            Text(
                text = "Selected: ${selectedPackages.size} apps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredApps) { app ->
                    val pm = context.packageManager
                    val appLabel = app.loadLabel(pm).toString()
                    val isSelected = selectedPackages.contains(app.packageName)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .clickable {
                                if (isSelected) selectedPackages.remove(app.packageName)
                                else selectedPackages.add(app.packageName)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = appLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (isSelected) selectedPackages.remove(app.packageName)
                                else selectedPackages.add(app.packageName)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val prefs = extContext.getSharedPreferences("cluster_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("cluster:$clusterName", selectedPackages.joinToString(",")).apply()
                        Toast.makeText(context, "Cluster '$clusterName' saved successfully", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    enabled = selectedPackages.isNotEmpty() && clusterName.isNotEmpty()
                ) {
                    Text("Save Cluster", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("thor://extension/trigger?class=com.valhalla.thor.ext.automation.AutomationCluster&triggerId=toggle:$clusterName")
                            )
                            val shortcut = ShortcutInfo.Builder(context, "shortcut:$clusterName")
                                .setShortLabel(clusterName)
                                .setLongLabel("Toggle $clusterName")
                                .setIcon(Icon.createWithResource(context, android.R.drawable.ic_lock_power_off))
                                .setIntent(intent)
                                .build()

                            shortcutManager.requestPinShortcut(shortcut, null)
                            Toast.makeText(context, "Requesting homescreen shortcut pinning", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Launcher shortcut pinning not supported", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    enabled = selectedPackages.isNotEmpty() && clusterName.isNotEmpty()
                ) {
                    Text("Add Shortcut", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val intent = Intent().setClassName("com.valhalla.thor.ext.automation", "com.valhalla.thor.ext.automation.AlarmReceiver").apply {
                        putExtra("cluster_name", clusterName)
                        putExtra("action", "freeze")
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        clusterName.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = System.currentTimeMillis()
                        set(Calendar.HOUR_OF_DAY, 21)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
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

                    Toast.makeText(context, "Scheduled daily freeze at 21:00", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                enabled = selectedPackages.isNotEmpty() && clusterName.isNotEmpty()
            ) {
                Text("Schedule Daily 9:00 PM Freeze", fontWeight = FontWeight.Bold)
            }
        }
    }
}
