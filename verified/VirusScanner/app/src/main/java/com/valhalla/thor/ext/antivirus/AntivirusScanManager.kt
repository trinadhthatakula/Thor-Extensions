package com.valhalla.thor.ext.antivirus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateListOf
import com.valhalla.thor.ext.antivirus.analysis.*
import com.valhalla.thor.ext.antivirus.executor.PrivilegedActionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object AntivirusScanManager {
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _scannedCount = MutableStateFlow(0)
    val scannedCount = _scannedCount.asStateFlow()

    private val _threatCount = MutableStateFlow(0)
    val threatCount = _threatCount.asStateFlow()

    private val _currentScannedPackage = MutableStateFlow("")
    val currentScannedPackage = _currentScannedPackage.asStateFlow()

    val scanResults = mutableStateListOf<ScanResultItem>()

    private var scanJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startScan(context: Context, scanType: Int) {
        if (_isScanning.value) return
        _isScanning.value = true
        scanResults.clear()
        _scannedCount.value = 0
        _threatCount.value = 0
        _currentScannedPackage.value = ""

        val serviceIntent = Intent(context, AntivirusScanService::class.java).apply {
            putExtra("scan_type", scanType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopScan(context: Context) {
        _isScanning.value = false
        scanJob?.cancel()
        scanJob = null
        context.stopService(Intent(context, AntivirusScanService::class.java))
    }

    fun performScanLoop(context: Context, scanType: Int, onProgress: () -> Unit, onFinished: () -> Unit) {
        scanJob?.cancel()
        scanJob = managerScope.launch {
            try {
                val staticEngine = StaticAnalysisEngine(context)
                val sigVerifier = SignatureVerifier(context)
                val permAuditor = PermissionAuditor(context)

                if (scanType == 0) {
                    val pm = context.packageManager
                    val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }

                    for (app in installedApps) {
                        if (!_isScanning.value) break
                        val label = app.loadLabel(pm).toString()
                        _currentScannedPackage.value = label
                        onProgress()

                        val sha256 = staticEngine.computeApkSha256(app.packageName) ?: "0000000000000000"
                        val sigPinValid = sigVerifier.verifyDeveloperSignaturePin(app.packageName)
                        val sigHash = sigVerifier.getSigningCertSha256(app.packageName)
                        val audit = permAuditor.auditPermissionProfile(app.packageName)

                        var score = audit.score
                        if (!sigPinValid) score += 60

                        val classification = when {
                            score >= 60 -> "MALICIOUS"
                            score >= 30 -> "SUSPICIOUS"
                            else -> "CLEAN"
                        }

                        if (classification != "CLEAN") {
                            _threatCount.value++
                        }

                        _scannedCount.value++
                        withContext(Dispatchers.Main) {
                            scanResults.add(
                                ScanResultItem(
                                    packageName = app.packageName,
                                    displayName = label,
                                    sha256 = sha256,
                                    riskScore = score,
                                    classification = classification,
                                    signatureHash = sigHash,
                                    auditResult = audit,
                                    isStorageApk = false
                                )
                            )
                        }
                        delay(120)
                    }
                } else {
                    val downloadsDir = File(android.os.Environment.getExternalStorageDirectory(), "Download")
                    val apkFiles = mutableListOf<File>()
                    if (downloadsDir.exists() && downloadsDir.isDirectory) {
                        downloadsDir.listFiles()?.forEach { file ->
                            if (file.isFile && file.name.endsWith(".apk")) {
                                apkFiles.add(file)
                            }
                        }
                    }

                    for (file in apkFiles) {
                        if (!_isScanning.value) break
                        _currentScannedPackage.value = file.name
                        onProgress()

                        val pm = context.packageManager
                        val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS)
                        val packageName = packageInfo?.packageName ?: "unknown.package"
                        val label = packageInfo?.applicationInfo?.loadLabel(pm)?.toString() ?: file.name

                        val sha256 = staticEngine.computeApkSha256(file.absolutePath) ?: "0000000000000000"
                        val audit = permAuditor.auditPermissionProfile(file.absolutePath)

                        val classification = when {
                            audit.score >= 60 -> "MALICIOUS"
                            audit.score >= 30 -> "SUSPICIOUS"
                            else -> "CLEAN"
                        }

                        if (classification != "CLEAN") {
                            _threatCount.value++
                        }

                        _scannedCount.value++
                        withContext(Dispatchers.Main) {
                            scanResults.add(
                                ScanResultItem(
                                    packageName = packageName,
                                    displayName = label,
                                    sha256 = sha256,
                                    riskScore = audit.score,
                                    classification = classification,
                                    signatureHash = null,
                                    auditResult = audit,
                                    isStorageApk = true,
                                    apkFilePath = file.absolutePath
                                )
                            )
                        }
                        delay(120)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
                onFinished()
            }
        }
    }
}
