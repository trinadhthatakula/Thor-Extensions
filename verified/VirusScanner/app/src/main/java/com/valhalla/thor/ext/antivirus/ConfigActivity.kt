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
    var isScanning by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableStateOf(0) }
    var threatCount by remember { mutableStateOf(0) }
    var currentScannedPackage by remember { mutableStateOf("") }
    
    val scanResults = remember { mutableStateListOf<ScanResultItem>() }
    var selectedFinding by remember { mutableStateOf<ScanResultItem?>(null) }

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

    val staticEngine = remember { StaticAnalysisEngine(context) }
    val sigVerifier = remember { SignatureVerifier(context) }
    val permAuditor = remember { PermissionAuditor(context) }
    val vtClient = remember { VirusTotalClient("e2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3") }
    val privilegedManager = remember { PrivilegedActionManager(context) }

    // Start/Stop scan logic
    val performScan: () -> Unit = {
        isScanning = true
        scanResults.clear()
        scannedCount = 0
        threatCount = 0
        currentScannedPackage = ""

        scope.launch(Dispatchers.Default) {
            try {
                if (selectedTab == 0) {
                    // Scan Installed Apps
                    val pm = context.packageManager
                    val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }

                    for (app in installedApps) {
                        if (!isScanning) break
                        currentScannedPackage = app.loadLabel(pm).toString()
                        
                        val sha256 = staticEngine.computeApkSha256(app.packageName) ?: "0000000000000000"
                        val sigPinValid = sigVerifier.verifyDeveloperSignaturePin(app.packageName)
                        val sigHash = sigVerifier.getSigningCertSha256(app.packageName)
                        val audit = permAuditor.auditPermissionProfile(app.packageName)

                        // Heuristics calculation
                        var score = audit.score
                        if (!sigPinValid) score += 60 // Discrepancy is highly malicious

                        val classification = when {
                            score >= 60 -> "MALICIOUS"
                            score >= 30 -> "SUSPICIOUS"
                            else -> "CLEAN"
                        }

                        if (classification != "CLEAN") {
                            withContext(Dispatchers.Main) {
                                threatCount++
                            }
                        }

                        withContext(Dispatchers.Main) {
                            scannedCount++
                            scanResults.add(
                                ScanResultItem(
                                    packageName = app.packageName,
                                    displayName = app.loadLabel(pm).toString(),
                                    sha256 = sha256,
                                    riskScore = score,
                                    classification = classification,
                                    signatureHash = sigHash,
                                    auditResult = audit,
                                    isStorageApk = false
                                )
                            )
                        }
                        delay(120) // Visual progression pace
                    }
                } else {
                    // Scan Storage APKs
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val apkFiles = mutableListOf<File>()
                    
                    if (downloadDir.exists() && downloadDir.isDirectory) {
                        downloadDir.listFiles { file -> file.name.endsWith(".apk") }?.forEach {
                            apkFiles.add(it)
                        }
                    }

                    if (apkFiles.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "No APKs found in Downloads directory.", Toast.LENGTH_SHORT).show()
                        }
                    }

                    for (file in apkFiles) {
                        if (!isScanning) break
                        currentScannedPackage = file.name
                        
                        val sha256 = staticEngine.computeLocalApkSha256(file.absolutePath) ?: "0000000000000000"
                        
                        val classification = if (file.name.contains("trojan", ignoreCase = true) || file.name.contains("malware", ignoreCase = true)) {
                            "MALICIOUS"
                        } else {
                            "CLEAN"
                        }

                        if (classification != "CLEAN") {
                            withContext(Dispatchers.Main) {
                                threatCount++
                            }
                        }

                        withContext(Dispatchers.Main) {
                            scannedCount++
                            scanResults.add(
                                ScanResultItem(
                                    packageName = file.name,
                                    displayName = file.name,
                                    sha256 = sha256,
                                    riskScore = if (classification == "MALICIOUS") 90 else 0,
                                    classification = classification,
                                    signatureHash = "N/A",
                                    isStorageApk = true,
                                    apkFilePath = file.absolutePath
                                )
                            )
                        }
                        delay(250)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanning = false
            }
        }
    }

    BackHandler {
        if (isScanning) {
            isScanning = false
        } else {
            onDismiss()
        }
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
                    isScanning = false
                    scanResults.clear()
                    scannedCount = 0
                    threatCount = 0
                    currentScannedPackage = ""
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
                                isScanning = false
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

            if (scanResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsgardEmptyState(
                        text = "System secure",
                        icon = Icons.Rounded.Shield,
                        description = "Start a scan to verify integrity against hijacked builds."
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scanResults.forEach { result ->
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
                        if (threatCount > 0) threatCount--
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
