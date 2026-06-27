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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.extension.api.ShellExecutor
import java.util.Calendar

class AutomationCluster : AutomationExtension {
    override val name: String = "Thor Cluster Automator"
    override val description: String = "Automate freezing and unfreezing of custom app clusters."
    override val version: String = "1.00.0"
    override val author: String = "Thor Team"

    private var currentScreen by mutableStateOf(AutoScreen.CLUSTERS_LIST)
    private var selectedClusterName by mutableStateOf("")
    private var editingClusterName by mutableStateOf<String?>(null)

    override fun onBackPressed(): Boolean {
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

    enum class AutoScreen {
        CLUSTERS_LIST,
        CREATE_EDIT_CLUSTER,
        CLUSTER_DETAILS
    }

    @Composable
    override fun ConfigurationScreen(shellExecutor: ShellExecutor, onBack: () -> Unit) {
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            currentScreen = AutoScreen.CLUSTERS_LIST
            selectedClusterName = ""
            editingClusterName = null
        }

        val extContext = remember {
            try {
                context.createPackageContext("com.valhalla.thor.ext.automation", Context.CONTEXT_IGNORE_SECURITY)
            } catch (_: Exception) {
                context
            }
        }
        val prefs = remember(extContext) { extContext.getSharedPreferences("cluster_prefs", Context.MODE_PRIVATE) }

        var clustersList by remember {
            mutableStateOf(prefs.getStringSet("cluster_names", emptySet())?.toList() ?: emptyList())
        }

        val refreshClusters = {
            clustersList = prefs.getStringSet("cluster_names", emptySet())?.toList() ?: emptyList()
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
                    prefs = prefs
                )
            }
            AutoScreen.CLUSTER_DETAILS -> {
                ClusterDetailsScreen(
                    context = context,
                    clusterName = selectedClusterName,
                    shellExecutor = shellExecutor,
                    onBack = { currentScreen = AutoScreen.CLUSTERS_LIST },
                    onEditCluster = { name ->
                        editingClusterName = name
                        currentScreen = AutoScreen.CREATE_EDIT_CLUSTER
                    },
                    onDeleteCluster = { name ->
                        val currentSet = prefs.getStringSet("cluster_names", emptySet()) ?: emptySet()
                        val newSet = currentSet.toMutableSet()
                        newSet.remove(name)
                        prefs.edit()
                            .putStringSet("cluster_names", newSet)
                            .remove("cluster:$name")
                            .remove("schedule:$name")
                            .apply()
                        refreshClusters()
                        currentScreen = AutoScreen.CLUSTERS_LIST
                    },
                    prefs = prefs
                )
            }
            AutoScreen.CREATE_EDIT_CLUSTER -> {
                CreateEditClusterScreen(
                    context = context,
                    editingClusterName = editingClusterName,
                    onBack = {
                        if (editingClusterName != null) {
                            currentScreen = AutoScreen.CLUSTER_DETAILS
                        } else {
                            currentScreen = AutoScreen.CLUSTERS_LIST
                        }
                    },
                    onSave = { name, packages ->
                        val currentSet = prefs.getStringSet("cluster_names", emptySet()) ?: emptySet()
                        val newSet = currentSet.toMutableSet()
                        newSet.add(name)
                        prefs.edit()
                            .putStringSet("cluster_names", newSet)
                            .putString("cluster:$name", packages.joinToString(","))
                            .apply()
                        refreshClusters()
                        selectedClusterName = name
                        currentScreen = AutoScreen.CLUSTER_DETAILS
                    },
                    prefs = prefs
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClustersListScreen(
    context: Context,
    clustersList: List<String>,
    shellExecutor: ShellExecutor,
    onBack: () -> Unit,
    onClusterClick: (String) -> Unit,
    onAddCluster: () -> Unit,
    prefs: android.content.SharedPreferences
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                title = {
                    Text("App Clusters")
                },
                subtitle = {
                    Text("Manage/trigger groups of apps collectively.")
                },
                windowInsets = WindowInsets(0,0,0,0)
            )
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
        contentWindowInsets = WindowInsets(0,0,0,0)
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
                    items(clustersList) { name ->
                        val packagesString = prefs.getString("cluster:$name", "") ?: ""
                        val appCount = if (packagesString.isEmpty()) 0 else packagesString.split(",").size

                        Card(
                            onClick = { onClusterClick(name) },
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.fillMaxWidth()
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
                                        text = name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$appCount apps",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (packagesString.isNotEmpty()) {
                                                val packageList = packagesString.split(",")
                                                for (pkg in packageList) {
                                                    shellExecutor.execute("pm disable-user --user 0 $pkg")
                                                }
                                                Toast.makeText(context, "Frozen $name", Toast.LENGTH_SHORT).show()
                                            }
                                        },
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
                                        onClick = {
                                            if (packagesString.isNotEmpty()) {
                                                val packageList = packagesString.split(",")
                                                for (pkg in packageList) {
                                                    shellExecutor.execute("pm enable $pkg")
                                                }
                                                Toast.makeText(context, "Unfrozen $name", Toast.LENGTH_SHORT).show()
                                            }
                                        },
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
    clusterName: String,
    shellExecutor: ShellExecutor,
    onBack: () -> Unit,
    onEditCluster: (String) -> Unit,
    onDeleteCluster: (String) -> Unit,
    prefs: android.content.SharedPreferences
) {
    val packagesString = remember(clusterName) { prefs.getString("cluster:$clusterName", "") ?: "" }
    val packageList = remember(packagesString) { packagesString.split(",").filter { it.isNotEmpty() } }

    var isScheduled by remember { mutableStateOf(prefs.getBoolean("schedule:$clusterName", false)) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = clusterName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
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

            Text(
                text = "Trigger Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        for (pkg in packageList) {
                            shellExecutor.execute("pm disable-user --user 0 $pkg")
                        }
                        Toast.makeText(context, "Cluster frozen", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Freeze", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        for (pkg in packageList) {
                            shellExecutor.execute("pm enable $pkg")
                        }
                        Toast.makeText(context, "Cluster unfrozen", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Unfreeze", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
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
                            Toast.makeText(context, "Requesting shortcut pinning", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Shortcut pinning not supported", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Shortcut", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

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

                        if (isScheduled) {
                            alarmManager.cancel(pendingIntent)
                            prefs.edit().putBoolean("schedule:$clusterName", false).apply()
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
                            prefs.edit().putBoolean("schedule:$clusterName", true).apply()
                            isScheduled = true
                            Toast.makeText(context, "Scheduled daily 9:00 PM", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScheduled) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isScheduled) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Notifications, contentDescription = null)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(if (isScheduled) "Active" else "9PM Daily", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onEditCluster(clusterName) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit")
                }

                OutlinedButton(
                    onClick = { onDeleteCluster(clusterName) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete")
                }
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

                    LaunchedEffect(pkg) {
                        try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            appLabel = info.loadLabel(pm).toString()
                            isFrozen = !info.enabled
                        } catch (e: Exception) {
                            val statusResult = shellExecutor.execute("pm list packages -d $pkg")
                            isFrozen = statusResult.second?.contains(pkg) == true
                        }
                    }

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
                                Text(
                                    text = appLabel.firstOrNull()?.toString() ?: "?",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = if (isFrozen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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

                            Text(
                                text = if (isFrozen) "Frozen" else "Active",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Normal,
                                color = if (isFrozen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
    editingClusterName: String?,
    onBack: () -> Unit,
    onSave: (String, List<String>) -> Unit,
    prefs: android.content.SharedPreferences
) {
    var clusterName by remember { mutableStateOf(editingClusterName ?: "") }
    var searchQuery by remember { mutableStateOf("") }
    val selectedPackages = remember { mutableStateListOf<String>() }

    LaunchedEffect(editingClusterName) {
        if (editingClusterName != null) {
            val saved = prefs.getString("cluster:$editingClusterName", "") ?: ""
            if (saved.isNotEmpty()) {
                selectedPackages.clear()
                selectedPackages.addAll(saved.split(","))
            }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (editingClusterName != null) "Edit Cluster" else "New Cluster",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
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
                onValueChange = { if (editingClusterName == null) clusterName = it },
                label = { Text("Cluster Name") },
                shape = RoundedCornerShape(12.dp),
                enabled = editingClusterName == null,
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
