package com.valhalla.thor.ext.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.asgard.components.AsgardHeader
import com.valhalla.asgard.components.StatusChip
import com.valhalla.asgard.components.AsgardEmptyState
import com.valhalla.asgard.components.AsgardSectionCard
import com.valhalla.asgard.components.AsgardSettingRow
import com.valhalla.asgard.components.AsgardSettingToggleRow
import com.valhalla.asgard.components.AsgardSearchBar
import com.valhalla.asgard.expressivePress
import com.valhalla.thor.extension.api.ThorExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * The automation extension's configuration UI, presented as a full-screen Scaffold (the richer
 * cluster-management flow — list, detail grid, app picker — needs the room, unlike Strombringer's
 * compact bottom sheet).
 *
 * It runs in the EXTENSION'S OWN process (Thor starts it via the CONFIGURE intent), NOT inside Thor.
 * That isolation is deliberate: a config screen rendered inside Thor would have to link, at runtime,
 * against Thor's minified Compose/Asgard/kotlin-stdlib (R8-stripped, parent-first classloading),
 * which is unwinnable across an independently-built plugin boundary. Here the Activity has its own
 * full Compose/Asgard, so nothing crosses the boundary.
 *
 * Two consequences of running out-of-process, handled below:
 *  • No ShellExecutor here — live freeze/unfreeze is forwarded to Thor's ExtensionOpsProvider (see
 *    [ThorOps]), the same path [AlarmReceiver] uses; frozen-state is read locally via PackageManager.
 *  • Cluster persistence goes through [AutomationConfigProvider]'s private prefs (same process, so the
 *    Activity reads/writes them directly); [AlarmReceiver] reads the same prefs to resolve packages.
 */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Thor passes its resolved theme so this UI (own process) matches Thor's look. Absent (e.g.
        // launched directly via adb) -> null -> AutomationTheme falls back to Thor's defaults.
        val themeArgs = intent?.let {
            if (it.hasExtra(ThorExtensionContract.EXTRA_THEME_MODE) || it.hasExtra(ThorExtensionContract.EXTRA_AMOLED)) {
                ThemeArgs(
                    themeMode = it.getStringExtra(ThorExtensionContract.EXTRA_THEME_MODE) ?: "SYSTEM",
                    dynamicColor = it.getBooleanExtra(ThorExtensionContract.EXTRA_DYNAMIC_COLOR, false),
                    amoled = it.getBooleanExtra(ThorExtensionContract.EXTRA_AMOLED, false),
                )
            } else null
        }
        setContent {
            AutomationTheme(themeArgs) {
                AutomationConfigRoot(onExit = { finish() })
            }
        }
    }
}

private enum class AutoScreen { CLUSTERS_LIST, CREATE_EDIT_CLUSTER, CLUSTER_DETAILS }

/** Loads the persisted cluster list from the (same-process) config prefs. */
private fun loadClusters(context: Context): List<AppCluster> {
    val json = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        .getString(Config.KEY_SAVED_CLUSTERS, null) ?: return emptyList()
    val rawList = runCatching { Json.decodeFromString<List<AppCluster>>(json) }.getOrDefault(emptyList())

    // Auto-migrate legacy isScheduled schedule to isFreezeScheduled schedule
    var modified = false
    val migrated = rawList.map { cluster ->
        if (cluster.isScheduled && !cluster.isFreezeScheduled) {
            modified = true
            cluster.copy(isScheduled = false, isFreezeScheduled = true)
        } else {
            cluster
        }
    }

    if (modified) {
        saveClusters(context, migrated)
    }

    return migrated
}

/** Persists the cluster list to the config prefs; [AlarmReceiver] reads it back to resolve packages. */
private fun saveClusters(context: Context, clusters: List<AppCluster>) {
    context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(Config.KEY_SAVED_CLUSTERS, Json.encodeToString(clusters))
        .apply()
}

/** Utility to format an hour/minute pair to standard 12-hour AM/PM string. */
private fun formatTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format("%02d:%02d %s", displayHour, minute, amPm)
}

/** True if [pkg] is currently frozen (disabled), read locally without root. */
private fun isPackageFrozen(pm: PackageManager, pkg: String): Boolean = runCatching {
    when (pm.getApplicationEnabledSetting(pkg)) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> true
        else -> false
    }
}.getOrDefault(false)

/**
 * App icon loader for this process. The api's `AppIcon` composable (removed in 3.0.0) resolved via
 * Thor's Coil keyer/fetcher, absent here — so we load the icon Drawable off the main thread and hand
 * the resulting Bitmap to Coil3's [AsyncImage] (a Bitmap is a first-class Coil model on Android).
 */
@Composable
private fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
                ).loadIcon(pm).toBitmap()
            }.getOrNull()
        }
    }
    AsyncImage(
        model = bitmap,
        contentDescription = null,
        modifier = modifier,
        colorFilter = colorFilter,
    )
}

@Composable
private fun AutomationConfigRoot(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(AutoScreen.CLUSTERS_LIST) }
    var selectedClusterName by remember { mutableStateOf("") }
    var editingClusterName by remember { mutableStateOf<String?>(null) }
    var clustersList by remember { mutableStateOf<List<AppCluster>>(emptyList()) }

    fun reload() {
        scope.launch { clustersList = withContext(Dispatchers.IO) { loadClusters(context) } }
    }

    LaunchedEffect(Unit) { reload() }

    BackHandler {
        when (currentScreen) {
            AutoScreen.CREATE_EDIT_CLUSTER ->
                currentScreen = if (editingClusterName != null) AutoScreen.CLUSTER_DETAILS else AutoScreen.CLUSTERS_LIST
            AutoScreen.CLUSTER_DETAILS -> currentScreen = AutoScreen.CLUSTERS_LIST
            AutoScreen.CLUSTERS_LIST -> onExit()
        }
    }

    when (currentScreen) {
        AutoScreen.CLUSTERS_LIST -> {
            ClustersListScreen(
                context = context,
                clustersList = clustersList,
                onBack = onExit,
                onClusterClick = { name ->
                    selectedClusterName = name
                    currentScreen = AutoScreen.CLUSTER_DETAILS
                },
                onAddCluster = {
                    editingClusterName = null
                    currentScreen = AutoScreen.CREATE_EDIT_CLUSTER
                },
                onFreezeCluster = { name, packages ->
                    scope.launch(Dispatchers.IO) { ThorOps.run(context, "freeze", packages) }
                    Toast.makeText(context, "Freezing $name…", Toast.LENGTH_SHORT).show()
                },
                onUnfreezeCluster = { name, packages ->
                    scope.launch(Dispatchers.IO) { ThorOps.run(context, "unfreeze", packages) }
                    Toast.makeText(context, "Unfreezing $name…", Toast.LENGTH_SHORT).show()
                }
            )
        }
        AutoScreen.CLUSTER_DETAILS -> {
            val cluster = clustersList.firstOrNull { it.name == selectedClusterName }
            if (cluster != null) {
                ClusterDetailsScreen(
                    context = context,
                    cluster = cluster,
                    onBack = { currentScreen = AutoScreen.CLUSTERS_LIST },
                    onEditCluster = { name ->
                        editingClusterName = name
                        currentScreen = AutoScreen.CREATE_EDIT_CLUSTER
                    },
                    onDeleteCluster = { name ->
                        scope.launch {
                            val updated = clustersList.filter { it.name != name }
                            withContext(Dispatchers.IO) { saveClusters(context, updated) }
                            AlarmReceiver.cancelAllAlarms(context, name)
                            reload()
                            currentScreen = AutoScreen.CLUSTERS_LIST
                        }
                    },
                    onUpdateCluster = { updatedCluster ->
                        scope.launch {
                            val updated = clustersList.map {
                                if (it.name == updatedCluster.name) updatedCluster else it
                            }
                            withContext(Dispatchers.IO) { saveClusters(context, updated) }
                            reload()
                        }
                    }
                )
            } else {
                currentScreen = AutoScreen.CLUSTERS_LIST
            }
        }
        AutoScreen.CREATE_EDIT_CLUSTER -> {
            val editingCluster = editingClusterName?.let { name -> clustersList.firstOrNull { it.name == name } }
            CreateEditClusterScreen(
                context = context,
                editingCluster = editingCluster,
                onBack = {
                    currentScreen = if (editingClusterName != null) AutoScreen.CLUSTER_DETAILS else AutoScreen.CLUSTERS_LIST
                },
                onSave = { name, packages ->
                    scope.launch {
                        val updated = clustersList.toMutableList()
                        val idx = updated.indexOfFirst { it.name == name }
                        val isSched = editingCluster?.isScheduled ?: false
                        val newCluster = AppCluster(name, packages, isSched)
                        if (idx >= 0) updated[idx] = newCluster else updated.add(newCluster)
                        withContext(Dispatchers.IO) { saveClusters(context, updated) }
                        reload()
                        selectedClusterName = name
                        currentScreen = AutoScreen.CLUSTER_DETAILS
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClustersListScreen(
    context: Context,
    clustersList: List<AppCluster>,
    onBack: () -> Unit,
    onClusterClick: (String) -> Unit,
    onAddCluster: () -> Unit,
    onFreezeCluster: (String, List<String>) -> Unit,
    onUnfreezeCluster: (String, List<String>) -> Unit
) {
    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                AsgardHeader(title = "App Clusters", onNavigateBack = onBack)
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddCluster,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Cluster")
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            if (clustersList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AsgardEmptyState(
                        text = "No Clusters Saved",
                        description = "Create custom app groups to freeze/unfreeze them together manually or on a daily schedule.",
                        icon = Icons.Default.Info,
                        action = {
                            Button(
                                onClick = onAddCluster,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add First Cluster", fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(clustersList, key = { it.name }) { cluster ->
                        val src = remember { MutableInteractionSource() }
                        Card(
                            onClick = { onClusterClick(cluster.name) },
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            interactionSource = src,
                            modifier = Modifier
                                .fillMaxWidth()
                                .expressivePress(src)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cluster.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val scheduleText = when {
                                        cluster.isFreezeScheduled && cluster.isUnfreezeScheduled -> {
                                            "Freeze ${formatTime(cluster.freezeHour, cluster.freezeMinute)} • Unfreeze ${formatTime(cluster.unfreezeHour, cluster.unfreezeMinute)}"
                                        }
                                        cluster.isFreezeScheduled -> {
                                            "Freeze ${formatTime(cluster.freezeHour, cluster.freezeMinute)}"
                                        }
                                        cluster.isUnfreezeScheduled -> {
                                            "Unfreeze ${formatTime(cluster.unfreezeHour, cluster.unfreezeMinute)}"
                                        }
                                        else -> "No schedule"
                                    }
                                    Text(
                                        text = "${cluster.packages.size} apps • $scheduleText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { onFreezeCluster(cluster.name, cluster.packages) },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Freeze",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }

                                    IconButton(
                                        onClick = { onUnfreezeCluster(cluster.name, cluster.packages) },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Unfreeze",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterDetailsScreen(
    context: Context,
    cluster: AppCluster,
    onBack: () -> Unit,
    onEditCluster: (String) -> Unit,
    onDeleteCluster: (String) -> Unit,
    onUpdateCluster: (AppCluster) -> Unit
) {
    val clusterName = cluster.name
    val packageList = cluster.packages
    val scope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                AsgardHeader(title = clusterName, onNavigateBack = onBack)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Trigger Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsgardActionItem(
                    icon = Icons.Default.Lock,
                    label = "Freeze",
                    onClick = {
                        Toast.makeText(context, "Freezing cluster…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            withContext(Dispatchers.IO) { ThorOps.run(context, "freeze", cluster.packages) }
                            refreshTrigger++
                        }
                    },
                    iconTint = MaterialTheme.colorScheme.error
                )

                AsgardActionItem(
                    icon = Icons.Default.Refresh,
                    label = "Unfreeze",
                    onClick = {
                        Toast.makeText(context, "Unfreezing cluster…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            withContext(Dispatchers.IO) { ThorOps.run(context, "unfreeze", cluster.packages) }
                            refreshTrigger++
                        }
                    }
                )

                AsgardActionItem(
                    icon = Icons.Default.Edit,
                    label = "Edit",
                    onClick = { onEditCluster(clusterName) }
                )

                AsgardActionItem(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    onClick = { onDeleteCluster(clusterName) },
                    iconTint = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AsgardSectionCard(
                title = "Automated Schedule",
                modifier = Modifier.fillMaxWidth()
            ) {
                AsgardSettingToggleRow(
                    title = "Auto-Freeze Daily",
                    checked = cluster.isFreezeScheduled,
                    onCheckedChange = { checked ->
                        val updated = cluster.copy(isFreezeScheduled = checked)
                        if (checked) {
                            AlarmReceiver.scheduleAlarm(context, clusterName, "freeze", cluster.freezeHour, cluster.freezeMinute)
                            Toast.makeText(context, "Freeze scheduled daily at ${formatTime(cluster.freezeHour, cluster.freezeMinute)}", Toast.LENGTH_SHORT).show()
                        } else {
                            AlarmReceiver.cancelAlarm(context, clusterName, "freeze")
                            Toast.makeText(context, "Freeze schedule removed", Toast.LENGTH_SHORT).show()
                        }
                        onUpdateCluster(updated)
                    },
                    subtitle = "Automatically freeze cluster apps daily"
                )

                if (cluster.isFreezeScheduled) {
                    AsgardSettingRow(
                        title = "Freeze Time",
                        value = formatTime(cluster.freezeHour, cluster.freezeMinute),
                        icon = Icons.Default.Lock,
                        onClick = {
                            android.app.TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val updated = cluster.copy(freezeHour = hour, freezeMinute = minute)
                                    AlarmReceiver.scheduleAlarm(context, clusterName, "freeze", hour, minute)
                                    onUpdateCluster(updated)
                                    Toast.makeText(context, "Freeze rescheduled to ${formatTime(hour, minute)}", Toast.LENGTH_SHORT).show()
                                },
                                cluster.freezeHour,
                                cluster.freezeMinute,
                                false
                            ).show()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                AsgardSettingToggleRow(
                    title = "Auto-Unfreeze Daily",
                    checked = cluster.isUnfreezeScheduled,
                    onCheckedChange = { checked ->
                        val updated = cluster.copy(isUnfreezeScheduled = checked)
                        if (checked) {
                            AlarmReceiver.scheduleAlarm(context, clusterName, "unfreeze", cluster.unfreezeHour, cluster.unfreezeMinute)
                            Toast.makeText(context, "Unfreeze scheduled daily at ${formatTime(cluster.unfreezeHour, cluster.unfreezeMinute)}", Toast.LENGTH_SHORT).show()
                        } else {
                            AlarmReceiver.cancelAlarm(context, clusterName, "unfreeze")
                            Toast.makeText(context, "Unfreeze schedule removed", Toast.LENGTH_SHORT).show()
                        }
                        onUpdateCluster(updated)
                    },
                    subtitle = "Automatically unfreeze cluster apps daily"
                )

                if (cluster.isUnfreezeScheduled) {
                    AsgardSettingRow(
                        title = "Unfreeze Time",
                        value = formatTime(cluster.unfreezeHour, cluster.unfreezeMinute),
                        icon = Icons.Default.Refresh,
                        onClick = {
                            android.app.TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val updated = cluster.copy(unfreezeHour = hour, unfreezeMinute = minute)
                                    AlarmReceiver.scheduleAlarm(context, clusterName, "unfreeze", hour, minute)
                                    onUpdateCluster(updated)
                                    Toast.makeText(context, "Unfreeze rescheduled to ${formatTime(hour, minute)}", Toast.LENGTH_SHORT).show()
                                },
                                cluster.unfreezeHour,
                                cluster.unfreezeMinute,
                                false
                            ).show()
                        }
                    )
                }
            }

            // Clusters always freeze via disable/enable — Thor's Suspend mode does not apply here (its
            // suspend path is unavailable on release builds). Stated so users on Suspend mode aren't
            // surprised that cluster apps are disabled rather than suspended.
            Text(
                text = "Apps are frozen via disable/enable. Thor's Suspend mode isn't supported for clusters.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Cluster Apps (${packageList.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val pm = context.packageManager
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(packageList) { pkg ->
                    var appLabel by remember(pkg) { mutableStateOf(pkg) }
                    var isFrozen by remember(pkg) { mutableStateOf(false) }

                    LaunchedEffect(pkg, refreshTrigger) {
                        withContext(Dispatchers.IO) {
                            val label = runCatching {
                                pm.getApplicationInfo(
                                    pkg,
                                    PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
                                ).loadLabel(pm).toString()
                            }.getOrDefault(pkg)
                            val frozen = isPackageFrozen(pm, pkg)
                            withContext(Dispatchers.Main) {
                                appLabel = label
                                isFrozen = frozen
                            }
                        }
                    }

                    val saturationMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }
                    val grayscaleFilter = remember(saturationMatrix) { ColorFilter.colorMatrix(saturationMatrix) }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isFrozen) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.9f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isFrozen) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                AppIcon(
                                    packageName = pkg,
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    colorFilter = if (isFrozen) grayscaleFilter else null
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = appLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )

                            StatusChip(
                                text = if (isFrozen) "Frozen" else "Active",
                                containerColor = if (isFrozen) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateEditClusterScreen(
    context: Context,
    editingCluster: AppCluster?,
    onBack: () -> Unit,
    onSave: (String, List<String>) -> Unit
) {
    var clusterName by remember { mutableStateOf(editingCluster?.name ?: "") }
    var searchQuery by remember { mutableStateOf("") }
    val selectedPackages = remember { mutableStateListOf<String>() }

    LaunchedEffect(editingCluster) {
        if (editingCluster != null) {
            selectedPackages.clear()
            selectedPackages.addAll(editingCluster.packages)
        }
    }

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

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                AsgardHeader(
                    title = if (editingCluster != null) "Edit Cluster" else "New Cluster",
                    onNavigateBack = onBack
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = clusterName,
                onValueChange = { if (editingCluster == null) clusterName = it },
                label = { Text("Cluster Name") },
                shape = RoundedCornerShape(12.dp),
                enabled = editingCluster == null,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            AsgardSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search apps…",
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            )

            Text(
                text = "Select apps to include (${selectedPackages.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredApps) { app ->
                    val pm = context.packageManager
                    val appLabel = app.loadLabel(pm).toString()
                    val isSelected = selectedPackages.contains(app.packageName)

                    val src = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceContainerLow
                            )
                            .expressivePress(src)
                            .clickable(interactionSource = src, indication = null) {
                                if (isSelected) selectedPackages.remove(app.packageName)
                                else selectedPackages.add(app.packageName)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                packageName = app.packageName,
                                modifier = Modifier.fillMaxSize().padding(4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

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
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onSave(clusterName, selectedPackages.toList()) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                enabled = selectedPackages.isNotEmpty() && clusterName.trim().isNotEmpty()
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Cluster", fontWeight = FontWeight.Bold)
            }
        }
    }
}
