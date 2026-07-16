package com.valhalla.thor.ext.antivirus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.valhalla.asgard.*
import com.valhalla.asgard.components.*
import com.valhalla.asgard.charts.*
import com.valhalla.thor.ext.antivirus.analysis.*
import com.valhalla.thor.ext.antivirus.executor.PrivilegedActionManager
import com.valhalla.thor.ext.antivirus.network.ThreatReport
import com.valhalla.thor.ext.antivirus.network.VirusTotalClient
import com.valhalla.thor.extension.api.ThorExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * S.H.I.E.L.D. Configuration and Active Security Scanning UI.
 * Presented as a translucent bottom sheet (sliding up over Thor) to ensure a premium, integrated experience.
 */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeArgs = intent?.let {
            if (it.hasExtra(ThorExtensionContract.EXTRA_THEME_MODE) || it.hasExtra(ThorExtensionContract.EXTRA_AMOLED)) {
                ThemeArgs(
                    themeMode = it.getStringExtra(ThorExtensionContract.EXTRA_THEME_MODE) ?: "SYSTEM",
                    dynamicColor = it.getBooleanExtra(ThorExtensionContract.EXTRA_DYNAMIC_COLOR, false),
                    amoled = it.getBooleanExtra(ThorExtensionContract.EXTRA_AMOLED, false),
                )
            } else null
        }

        // Retrieve executor binder safely from intent extras
        val executorBinder = intent?.extras?.let { bundle ->
            androidx.core.app.BundleCompat.getBinder(bundle, "executor")
        }

        setContent {
            AntivirusTheme(themeArgs) {
                ShieldConfigSheet(
                    executorBinder = executorBinder,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

data class ScanResultItem(
    val packageName: String,
    val displayName: String,
    val sha256: String,
    val riskScore: Int,
    val classification: String,
    val signatureHash: String?,
    val auditResult: PermissionAuditResult? = null,
    val isStorageApk: Boolean = false,
    val apkFilePath: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShieldConfigSheet(
    executorBinder: IBinder?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Installed Apps, 1 = Storage APKs
    val isScanning by AntivirusScanManager.isScanning.collectAsStateWithLifecycle()
    val scannedCount by AntivirusScanManager.scannedCount.collectAsStateWithLifecycle()
    val threatCount by AntivirusScanManager.threatCount.collectAsStateWithLifecycle()
    val currentScannedPackage by AntivirusScanManager.currentScannedPackage.collectAsStateWithLifecycle()
    
    val scanResults = AntivirusScanManager.scanResults
    var selectedFinding by remember { mutableStateOf<ScanResultItem?>(null) }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val scanModes = listOf(
        ConnectedButtonGroupItem.IconWithLabel(
            icon = Icons.Rounded.Security,
            contentDescription = "Scan Apps",
            text = "Installed Apps"
        ),
        ConnectedButtonGroupItem.IconWithLabel(
            icon = Icons.Rounded.FolderZip,
            contentDescription = "Scan Local Storage",
            text = "Storage APKs"
        )
    )

    val privilegedManager = remember { PrivilegedActionManager(context) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            val filtered = uris.filter { uri ->
                val name = getFileName(context, uri).lowercase()
                name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".apkm") || 
                name.endsWith(".apkp") || name.endsWith(".xapk") || name.endsWith(".zip")
            }
            if (filtered.isNotEmpty()) {
                AntivirusScanManager.startScan(context, selectedTab, filtered)
            } else {
                Toast.makeText(context, "No supported file formats chosen (.apk, .apks, .apkm, .apkp, .xapk, .zip).", Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (selectedTab == 1) {
                pickerLauncher.launch(arrayOf("*/*"))
            } else {
                AntivirusScanManager.startScan(context, selectedTab)
            }
        } else {
            Toast.makeText(context, "Notification permission is required for background scanning progress.", Toast.LENGTH_LONG).show()
        }
    }

    val performScan: () -> Unit = {
        selectedFilter = "ALL"
        if (Build.VERSION.SDK_INT >= 33 && 
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            
            val granted = privilegedManager.executePrivilegedGrant(context.packageName, "android.permission.POST_NOTIFICATIONS", executorBinder)
            if (granted) {
                if (selectedTab == 1) {
                    pickerLauncher.launch(arrayOf("*/*"))
                } else {
                    AntivirusScanManager.startScan(context, selectedTab)
                }
            } else {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (selectedTab == 1) {
                pickerLauncher.launch(arrayOf("*/*"))
            } else {
                AntivirusScanManager.startScan(context, selectedTab)
            }
        }
    }

    BackHandler {
        onDismiss()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "S.H.I.E.L.D. SECURITY AGENCY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Strategic Hazard Intervention Espionage Logistics Directorate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = {
                    Toast.makeText(context, "Strategic logs are fully secure.", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(imageVector = Icons.Rounded.History, contentDescription = "Scan History", tint = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mode Toggle Bar (Connected Button Group)
            ConnectedButtonGroup(
                items = scanModes,
                selectedIndex = selectedTab,
                onItemSelected = { 
                    selectedTab = it 
                    AntivirusScanManager.stopScan(context)
                    selectedFilter = "ALL"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )

            // Scanning attention pulse
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                AsgardPulseRing(
                    color = if (threatCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ringSize = 120.dp,
                    pulsing = isScanning
                ) {
                    Button(
                        onClick = { 
                            if (isScanning) {
                                AntivirusScanManager.stopScan(context)
                            } else {
                                performScan()
                            }
                        },
                        modifier = Modifier.size(90.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (threatCount > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (threatCount > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (isScanning) "STOP" else "SCAN",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Real-Time Scanning Progression Label
            if (isScanning && currentScannedPackage.isNotEmpty()) {
                Text(
                    text = "Scanning: $currentScannedPackage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Real-time Metric Indicators (Stat Cards)
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsgardStatCard(
                    label = "Items Scanned",
                    value = scannedCount.toString(),
                    modifier = Modifier.width(140.dp),
                    icon = Icons.Rounded.Security,
                    iconTint = MaterialTheme.colorScheme.primary
                )
                AsgardStatCard(
                    label = "Threats Detected",
                    value = threatCount.toString(),
                    modifier = Modifier.width(140.dp),
                    icon = Icons.Rounded.BugReport,
                    iconTint = if (threatCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }

            if (scanResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))

                // Scan Results Header
                Text(
                    text = "Intelligence Findings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                // Horizontal Filter Chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("ALL", "MALICIOUS", "SUSPICIOUS").forEach { filter ->
                        val selected = selectedFilter == filter
                        val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(containerColor)
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                        }
                    }
                }

                // Filtered scan results
                val filteredResults = scanResults.filter { selectedFilter == "ALL" || it.classification == selectedFilter }

                if (filteredResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No $selectedFilter findings match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        filteredResults.forEach { result ->
                            AsgardListRow(
                                title = result.displayName,
                                subtitle = result.packageName,
                                caption = "SHA-256: ${result.sha256.take(16)}...",
                                icon = Icons.Rounded.BugReport,
                                onClick = { selectedFinding = result },
                                trailing = {
                                    val statusColor = when (result.classification) {
                                        "MALICIOUS" -> MaterialTheme.colorScheme.error
                                        "SUSPICIOUS" -> Color(0xFFFFB300)
                                        else -> Color(0xFF4CAF50)
                                    }
                                    StatusChip(
                                        text = result.classification,
                                        containerColor = statusColor.copy(alpha = 0.15f),
                                        contentColor = statusColor
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Findings Audit dialog
    selectedFinding?.let { finding ->
        AlertDialog(
            onDismissRequest = { selectedFinding = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = "Threat Detected", tint = MaterialTheme.colorScheme.error) },
            title = { Text("App Threat Analysis", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App: ${finding.displayName}", fontWeight = FontWeight.Bold)
                    Text("Package: ${finding.packageName}", style = MaterialTheme.typography.bodySmall)
                    Text("Risk Heuristic Score: ${finding.riskScore}/100", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    
                    finding.auditResult?.let { audit ->
                        Text("Permission Abuse Audit:", fontWeight = FontWeight.Bold)
                        Text("• Accessibility Service Abuse: ${if (audit.hasAccessibilityAbuse) "DETECTED (High Risk)" else "None"}", color = if (audit.hasAccessibilityAbuse) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                        Text("• Overlay Abuse (Overlays active): ${if (audit.hasOverlayAbuse) "DETECTED (Medium Risk)" else "None"}", color = if (audit.hasOverlayAbuse) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                        Text("• Persistence (Run at boot): ${if (audit.hasPersistenceAbuse) "DETECTED" else "None"}")
                    }

                    Text("SHA-256 Fingerprint:", fontWeight = FontWeight.Bold)
                    Text(finding.sha256, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val success = privilegedManager.executeUninstall(finding.packageName, executorBinder)
                    if (success) {
                        scanResults.remove(finding)
                        AntivirusScanManager.decrementThreatCount()
                        selectedFinding = null
                    }
                }) {
                    Text("Purge (Uninstall)", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val success = privilegedManager.executeFreeze(finding.packageName, executorBinder)
                        if (success) {
                            selectedFinding = null
                        }
                    }) {
                        Text("Quarantine (Freeze)")
                    }
                    TextButton(onClick = { selectedFinding = null }) {
                        Text("Dismiss")
                    }
                }
            }
        )
    }
}

private fun getFileName(context: Context, uri: android.net.Uri): String {
    var result = "temp.apk"
    try {
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == "temp.apk") {
            val path = uri.path
            val cut = path?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = path.substring(cut + 1)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return result
}
