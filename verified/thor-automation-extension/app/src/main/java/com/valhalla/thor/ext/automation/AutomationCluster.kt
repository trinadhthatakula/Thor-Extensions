@file:Suppress("unused")

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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.expressivePress
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.asgard.components.AsgardHeader
import com.valhalla.asgard.components.StatusChip
import com.valhalla.thor.extension.api.AppIcon
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.extension.api.ExtensionDataStore
import com.valhalla.thor.extension.api.Logger
import com.valhalla.thor.extension.api.ShellExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

@Serializable
data class AppCluster(
    val name: String,
    val packages: List<String>,
    val isScheduled: Boolean = false
)

@Suppress("unused")
class AutomationCluster : AutomationExtension {
    override val name: String = "Thor Cluster Automator"
    override val description: String = "Automate freezing and unfreezing of custom app clusters."
    override val version: String = "1.0.0"
    override val author: String = "Thor Team"

    private var currentScreen by mutableStateOf(AutoScreen.CLUSTERS_LIST)
    private var selectedClusterName by mutableStateOf("")
    private var editingClusterName by mutableStateOf<String?>(null)

    override fun onBackPressed(): Boolean {
        Logger.d("AutomationCluster", "onBackPressed! currentScreen = $currentScreen")
        return when (currentScreen) {
            AutoScreen.CREATE_EDIT_CLUSTER -> {
                currentScreen = if (editingClusterName != null) {
                    AutoScreen.CLUSTER_DETAILS
                } else {
                    AutoScreen.CLUSTERS_LIST
                }
                true
            }
            AutoScreen.CLUSTER_DETAILS -> {
                currentScreen = AutoScreen.CLUSTERS_LIST
                true
            }
            AutoScreen.CLUSTERS_LIST -> {
                false
            }
        }
    }

    override fun onTrigger(context: Context, eventType: String, shellExecutor: ShellExecutor, dataStore: ExtensionDataStore) {
        val parts = eventType.split(":")
        if (parts.size < 2) return
        val action = parts[0]
        val clusterName = parts[1]

        val clustersJson = dataStore.getString("saved_clusters") ?: return
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

    enum class AutoScreen {
        CLUSTERS_LIST,
        CREATE_EDIT_CLUSTER,
        CLUSTER_DETAILS
    }

    @Composable
    override fun ConfigurationScreen(shellExecutor: ShellExecutor, dataStore: ExtensionDataStore, onBack: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            currentScreen = AutoScreen.CLUSTERS_LIST
            selectedClusterName = ""
            editingClusterName = null
        }

        var clustersList by remember { mutableStateOf<List<AppCluster>>(emptyList()) }

        val loadClusters = {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val clustersJson = dataStore.getString("saved_clusters")
                val list = if (clustersJson.isNullOrEmpty()) {
                    emptyList()
                } else {
                    try {
                        Json.decodeFromString<List<AppCluster>>(clustersJson)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    clustersList = list
                }
            }
        }

        LaunchedEffect(dataStore) {
            loadClusters()
        }

        when (currentScreen) {
            AutoScreen.CLUSTERS_LIST -> {
                ClustersListScreen(
                    context = context,
                    clustersList = clustersList,
                    shellExecutor = shellExecutor,
                    onBack = onBack,
                    onClusterClick = { name ->
                        selectedClusterName = name
                        currentScreen = AutoScreen.CLUSTER_DETAILS
                    },
                    onAddCluster = {
                        editingClusterName = null
                        currentScreen = AutoScreen.CREATE_EDIT_CLUSTER
                    },
                    onFreezeCluster = { name, packages ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            for (pkg in packages) {
                                shellExecutor.execute("pm disable-user --user 0 $pkg")
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "Frozen $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUnfreezeCluster = { name, packages ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            for (pkg in packages) {
                                shellExecutor.execute("pm enable $pkg")
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "Unfrozen $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
            AutoScreen.CLUSTER_DETAILS -> {
                val cluster = clustersList.firstOrNull { it.name == selectedClusterName }
                if (cluster != null) {
                    ClusterDetailsScreen(
                        context = context,
                        cluster = cluster,
                        shellExecutor = shellExecutor,
                        onBack = { currentScreen = AutoScreen.CLUSTERS_LIST },
                        onEditCluster = { name ->
                            editingClusterName = name
                            currentScreen = AutoScreen.CREATE_EDIT_CLUSTER
                        },
                        onDeleteCluster = { name ->
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val updated = clustersList.filter { it.name != name }
                                dataStore.saveString("saved_clusters", Json.encodeToString(updated))
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loadClusters()
                                    currentScreen = AutoScreen.CLUSTERS_LIST
                                }
                            }
                        },
                        onScheduleToggle = { name, scheduleEnabled ->
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val updated = clustersList.map {
                                    if (it.name == name) it.copy(isScheduled = scheduleEnabled) else it
                                }
                                dataStore.saveString("saved_clusters", Json.encodeToString(updated))
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loadClusters()
                                }
                            }
                        }
                    )
                } else {
                    currentScreen = AutoScreen.CLUSTERS_LIST
                }
            }
            AutoScreen.CREATE_EDIT_CLUSTER -> {
                val editingCluster = if (editingClusterName != null) clustersList.firstOrNull { it.name == editingClusterName } else null
                CreateEditClusterScreen(
                    context = context,
                    editingCluster = editingCluster,
                    onBack = {
                        if (editingClusterName != null) {
                            currentScreen = AutoScreen.CLUSTER_DETAILS
                        } else {
                            currentScreen = AutoScreen.CLUSTERS_LIST
                        }
                    },
                    onSave = { name, packages ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val updated = clustersList.toMutableList()
                            val idx = updated.indexOfFirst { it.name == name }
                            val isSched = editingCluster?.isScheduled ?: false
                            val newCluster = AppCluster(name, packages, isSched)
                            if (idx >= 0) {
                                updated[idx] = newCluster
                            } else {
                                updated.add(newCluster)
                            }
                            dataStore.saveString("saved_clusters", Json.encodeToString(updated))
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                loadClusters()
                                selectedClusterName = name
                                currentScreen = AutoScreen.CLUSTER_DETAILS
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClustersListScreen(
    context: Context,
    clustersList: List<AppCluster>,
    shellExecutor: ShellExecutor,
    onBack: () -> Unit,
    onClusterClick: (String) -> Unit,
    onAddCluster: () -> Unit,
    onFreezeCluster: (String, List<String>) -> Unit,
    onUnfreezeCluster: (String, List<String>) -> Unit
) {
    Scaffold(
        topBar = {
            AsgardHeader(title = "App Clusters", onNavigateBack = onBack)
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Clusters Saved",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap + to create your first app group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                                    Text(
                                        text = "${cluster.packages.size} apps",
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
fun ClusterDetailsScreen(
    context: Context,
    cluster: AppCluster,
    shellExecutor: ShellExecutor,
    onBack: () -> Unit,
    onEditCluster: (String) -> Unit,
    onDeleteCluster: (String) -> Unit,
    onScheduleToggle: (String, Boolean) -> Unit
) {
    val clusterName = cluster.name
    val packageList = cluster.packages
    var isScheduled by remember(cluster) { mutableStateOf(cluster.isScheduled) }
    val scope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            AsgardHeader(title = clusterName, onNavigateBack = onBack)
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
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            for (pkg in packageList) {
                                shellExecutor.execute("pm disable-user --user 0 $pkg")
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "Cluster frozen", Toast.LENGTH_SHORT).show()
                                refreshTrigger++
                            }
                        }
                    },
                    iconTint = MaterialTheme.colorScheme.error
                )

                AsgardActionItem(
                    icon = Icons.Default.Refresh,
                    label = "Unfreeze",
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            for (pkg in packageList) {
                                shellExecutor.execute("pm enable $pkg")
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "Cluster unfrozen", Toast.LENGTH_SHORT).show()
                                refreshTrigger++
                            }
                        }
                    }
                )

                AsgardActionItem(
                    icon = Icons.Default.Share,
                    label = "Shortcut",
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
                            Toast.makeText(context, "Requesting shortcut pinning", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Shortcut pinning not supported", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                AsgardActionItem(
                    icon = Icons.Default.Notifications,
                    label = if (isScheduled) "Active" else "9PM Daily",
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

                        if (isScheduled) {
                            alarmManager.cancel(pendingIntent)
                            onScheduleToggle(clusterName, false)
                            isScheduled = false
                            Toast.makeText(context, "Schedule removed", Toast.LENGTH_SHORT).show()
                        } else {
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
                            onScheduleToggle(clusterName, true)
                            isScheduled = true
                            Toast.makeText(context, "Scheduled daily 9:00 PM", Toast.LENGTH_SHORT).show()
                        }
                    },
                    iconTint = if (isScheduled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val info = pm.getApplicationInfo(pkg, 0)
                                val label = info.loadLabel(pm).toString()
                                val enabled = info.enabled
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    appLabel = label
                                    isFrozen = !enabled
                                }
                            } catch (e: Exception) {
                                val statusResult = shellExecutor.execute("pm list packages -d $pkg")
                                val frozen = statusResult.second?.contains(pkg) == true
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isFrozen = frozen
                                }
                            }
                        }
                    }

                    val saturationMatrix = remember { androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) } }
                    val grayscaleFilter = remember(saturationMatrix) { androidx.compose.ui.graphics.ColorFilter.colorMatrix(saturationMatrix) }

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
fun CreateEditClusterScreen(
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
            AsgardHeader(
                title = if (editingCluster != null) "Edit Cluster" else "New Cluster",
                onNavigateBack = onBack
            )
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

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Apps") },
                shape = RoundedCornerShape(12.dp),
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
