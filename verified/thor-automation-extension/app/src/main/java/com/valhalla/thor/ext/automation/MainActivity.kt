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
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

// Theme colors matching Thor's Asgardian theme
private val AsgardianDarkColorScheme = darkColorScheme(
    primary = Color(0xfff0ffd7),
    onPrimary = Color(0xff4c672c),
    primaryContainer = Color(0xffd5f6ab),
    onPrimaryContainer = Color(0xff445e25),
    secondary = Color(0xffc7c4dd),
    onSecondary = Color(0xff3f3e52),
    secondaryContainer = Color(0xff242436),
    onSecondaryContainer = Color(0xffa4a1b9),
    background = Color(0xff0e0e0e),
    surface = Color(0xff0e0e0e),
    onSurface = Color(0xffe5e5e5),
    onBackground = Color(0xffe5e5e5),
    surfaceVariant = Color(0xff262626),
    onSurfaceVariant = Color(0xffababab),
    outline = Color(0xff757575)
)

private val AsgardianLightColorScheme = lightColorScheme(
    primary = Color(0xff354e15),
    onPrimary = Color(0xffffffff),
    primaryContainer = Color(0xff4c662b),
    onPrimaryContainer = Color(0xfff0ffd7),
    secondary = Color(0xff55624c),
    onSecondary = Color(0xffffffff),
    secondaryContainer = Color(0xffd9e7cb),
    onSecondaryContainer = Color(0xff131f0d),
    background = Color(0xfff8faf3),
    surface = Color(0xfff8faf3),
    onSurface = Color(0xff191c18),
    onBackground = Color(0xff191c18),
    surfaceVariant = Color(0xffedefe8),
    onSurfaceVariant = Color(0xff43493e),
    outline = Color(0xff74796d)
)

@Composable
fun AsgardianExtensionTheme(
    themeMode: String,
    amoledMode: Boolean,
    dynamicColors: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val colorScheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context).run {
                    if (amoledMode) {
                        copy(background = Color.Black, surface = Color.Black, surfaceVariant = Color.Black)
                    } else {
                        this
                    }
                }
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> {
            if (amoledMode) {
                AsgardianDarkColorScheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color.Black
                )
            } else {
                AsgardianDarkColorScheme
            }
        }
        else -> AsgardianLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read styling extras passed from Thor app
        val themeMode = intent?.getStringExtra("theme_mode") ?: "system"
        val amoledMode = intent?.getBooleanExtra("amoled_mode", false) ?: false
        val dynamicColors = intent?.getBooleanExtra("dynamic_colors", false) ?: false

        setContent {
            AsgardianExtensionTheme(
                themeMode = themeMode,
                amoledMode = amoledMode,
                dynamicColors = dynamicColors
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AutomatorScreen(
                        context = this,
                        onSave = { clusterName, packages ->
                            saveCluster(clusterName, packages)
                        },
                        onPinShortcut = { clusterName ->
                            pinShortcut(clusterName)
                        },
                        onScheduleAlarm = { clusterName, hour, minute ->
                            scheduleAlarm(clusterName, hour, minute)
                        }
                    )
                }
            }
        }
    }

    private fun saveCluster(clusterName: String, packages: List<String>) {
        val prefs = getSharedPreferences("cluster_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("cluster:$clusterName", packages.joinToString(",")).apply()
        Toast.makeText(this, "Cluster '$clusterName' saved successfully", Toast.LENGTH_SHORT).show()
    }

    private fun pinShortcut(clusterName: String) {
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("thor://extension/trigger?class=com.valhalla.thor.ext.automation.AutomationCluster&triggerId=toggle:$clusterName")
            )
            val shortcut = ShortcutInfo.Builder(this, "shortcut:$clusterName")
                .setShortLabel(clusterName)
                .setLongLabel("Toggle $clusterName")
                .setIcon(Icon.createWithResource(this, android.R.drawable.ic_lock_power_off))
                .setIntent(intent)
                .build()

            shortcutManager.requestPinShortcut(shortcut, null)
            Toast.makeText(this, "Requesting homescreen shortcut pinning", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Launcher shortcut pinning not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleAlarm(clusterName: String, hour: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("cluster_name", clusterName)
            putExtra("action", "freeze")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            clusterName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }

        Toast.makeText(this, "Scheduled daily freeze at ${String.format("%02d:%02d", hour, minute)}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AutomatorScreen(
    context: Context,
    onSave: (String, List<String>) -> Unit,
    onPinShortcut: (String) -> Unit,
    onScheduleAlarm: (String, Int, Int) -> Unit
) {
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
                onClick = { onSave(clusterName, selectedPackages.toList()) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                enabled = selectedPackages.isNotEmpty() && clusterName.isNotEmpty()
            ) {
                Text("Save Cluster", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onPinShortcut(clusterName) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                enabled = selectedPackages.isNotEmpty() && clusterName.isNotEmpty()
            ) {
                Text("Add Shortcut", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { onScheduleAlarm(clusterName, 21, 0) },
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
