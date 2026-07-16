package com.valhalla.thor.ext.antivirus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import com.valhalla.thor.ext.antivirus.analysis.*
import com.valhalla.thor.ext.antivirus.network.VirusTotalClient
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

    fun startScan(context: Context, scanType: Int, pickedUris: List<Uri>? = null) {
        if (_isScanning.value) return
        _isScanning.value = true
        scanResults.clear()
        _scannedCount.value = 0
        _threatCount.value = 0
        _currentScannedPackage.value = ""

        val serviceIntent = Intent(context, AntivirusScanService::class.java).apply {
            putExtra("scan_type", scanType)
            if (pickedUris != null) {
                putParcelableArrayListExtra("uris", ArrayList(pickedUris))
            }
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

    fun decrementThreatCount() {
        if (_threatCount.value > 0) {
            _threatCount.value--
        }
    }

    private fun getFileFromUri(context: Context, uri: Uri): File? {
        return try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(uri, null, null, null, null)
            val name = if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else "temp.apk"
            } else {
                "temp.apk"
            }
            cursor?.close()

            val tempFile = File(context.cacheDir, name)
            contentResolver.openInputStream(uri).use { input ->
                tempFile.outputStream().use { output ->
                    input?.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun performScanLoop(
        context: Context, 
        scanType: Int, 
        pickedUris: List<Uri>?, 
        onProgress: () -> Unit, 
        onFinished: () -> Unit
    ) {
        scanJob?.cancel()
        scanJob = managerScope.launch {
            var vtClient: VirusTotalClient? = null
            try {
                val staticEngine = StaticAnalysisEngine(context)
                val sigVerifier = SignatureVerifier(context)
                val permAuditor = PermissionAuditor(context)
                val libScanner = StaticLibraryScanner()
                vtClient = VirusTotalClient("e2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3")

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
                        val vtReport = withContext(Dispatchers.IO) {
                            try {
                                vtClient.lookupFileHash(sha256)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }

                        val appInfo = pm.getApplicationInfo(app.packageName, 0)
                        val libScan = withContext(Dispatchers.IO) {
                            try {
                                libScanner.detectLibrariesInApk(appInfo.publicSourceDir)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                        val hasLibSu = libScan?.hasLibSu ?: false
                        val hasHiddenApiBypass = libScan?.hasHiddenApiBypass ?: false

                        val sigPinValid = sigVerifier.verifyDeveloperSignaturePin(app.packageName)
                        val sigHash = sigVerifier.getSigningCertSha256(app.packageName)
                        val audit = permAuditor.auditPermissionProfile(app.packageName)

                        var score = audit.score
                        if (!sigPinValid) score += 60

                        val localClassification = when {
                            score >= 60 -> "MALICIOUS"
                            score >= 30 -> "SUSPICIOUS"
                            else -> "CLEAN"
                        }

                        val classification = when {
                            vtReport?.classification == "MALICIOUS" -> "MALICIOUS"
                            vtReport?.classification == "SUSPICIOUS" -> "SUSPICIOUS"
                            else -> localClassification
                        }

                        val finalScore = when {
                            classification == "MALICIOUS" && score < 60 -> 85
                            classification == "SUSPICIOUS" && score < 30 -> 45
                            else -> score
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
                                    riskScore = finalScore,
                                    classification = classification,
                                    signatureHash = sigHash,
                                    auditResult = audit,
                                    isStorageApk = false,
                                    hasLibSu = hasLibSu,
                                    hasHiddenApiBypass = hasHiddenApiBypass
                                )
                            )
                        }
                        delay(120)
                    }
                } else {
                    val filesToScan = mutableListOf<File>()
                    if (pickedUris != null && pickedUris.isNotEmpty()) {
                        for (uri in pickedUris) {
                            val tempFile = getFileFromUri(context, uri)
                            if (tempFile != null) {
                                filesToScan.add(tempFile)
                            }
                        }
                    } else {
                        // Fallback to Scan Download folder
                        val downloadsDir = File(android.os.Environment.getExternalStorageDirectory(), "Download")
                        if (downloadsDir.exists() && downloadsDir.isDirectory) {
                            downloadsDir.listFiles()?.forEach { file ->
                                if (file.isFile && (
                                    file.name.endsWith(".apk", ignoreCase = true) || 
                                    file.name.endsWith(".apks", ignoreCase = true) || 
                                    file.name.endsWith(".apkm", ignoreCase = true) || 
                                    file.name.endsWith(".apkp", ignoreCase = true) || 
                                    file.name.endsWith(".xapk", ignoreCase = true) || 
                                    file.name.endsWith(".zip", ignoreCase = true)
                                )) {
                                    filesToScan.add(file)
                                }
                            }
                        }
                    }

                    for (file in filesToScan) {
                        if (!_isScanning.value) break
                        _currentScannedPackage.value = file.name
                        onProgress()

                        val pm = context.packageManager
                        val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS)
                        val packageName = packageInfo?.packageName ?: "unknown.package"
                        val label = packageInfo?.applicationInfo?.loadLabel(pm)?.toString() ?: file.name

                        val sha256 = staticEngine.computeLocalApkSha256(file.absolutePath) ?: "0000000000000000"
                        val vtReport = withContext(Dispatchers.IO) {
                            try {
                                vtClient.lookupFileHash(sha256)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }

                        val libScan = withContext(Dispatchers.IO) {
                            try {
                                libScanner.detectLibrariesInApk(file.absolutePath)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                        val hasLibSu = libScan?.hasLibSu ?: false
                        val hasHiddenApiBypass = libScan?.hasHiddenApiBypass ?: false

                        val audit = permAuditor.auditPermissionProfile(packageName)

                        val localClassification = when {
                            audit.score >= 60 -> "MALICIOUS"
                            audit.score >= 30 -> "SUSPICIOUS"
                            file.name.contains("trojan", ignoreCase = true) || file.name.contains("malware", ignoreCase = true) -> "MALICIOUS"
                            else -> "CLEAN"
                        }

                        val classification = when {
                            vtReport?.classification == "MALICIOUS" -> "MALICIOUS"
                            vtReport?.classification == "SUSPICIOUS" -> "SUSPICIOUS"
                            else -> localClassification
                        }

                        val finalScore = when {
                            classification == "MALICIOUS" && audit.score < 60 -> 85
                            classification == "SUSPICIOUS" && audit.score < 30 -> 45
                            else -> audit.score
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
                                    riskScore = finalScore,
                                    classification = classification,
                                    signatureHash = null,
                                    auditResult = audit,
                                    isStorageApk = true,
                                    apkFilePath = file.absolutePath,
                                    hasLibSu = hasLibSu,
                                    hasHiddenApiBypass = hasHiddenApiBypass
                                )
                            )
                        }

                        // Clean up temporary cache file if it was created under the cache dir
                        if (file.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                            try {
                                file.delete()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        delay(120)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
                try {
                    vtClient?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                onFinished()
            }
        }
    }
}
