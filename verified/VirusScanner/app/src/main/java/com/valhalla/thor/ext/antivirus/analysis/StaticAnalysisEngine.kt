package com.valhalla.thor.ext.antivirus.analysis

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StaticAnalysisEngine(private val context: Context) {

    /**
     * Resolves absolute base APK path and computes SHA-256 hash using chunked buffer streams.
     */
    suspend fun computeApkSha256(packageName: String): String? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val apkFile = File(appInfo.publicSourceDir)
            if (!apkFile.exists()) return@withContext null
            
            computeFileHash(apkFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Computes the SHA-256 hash for local storage files.
     */
    suspend fun computeLocalApkSha256(filePath: String): String? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists()) computeFileHash(file) else null
    }

    private fun computeFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192) // 8KB buffer size
        FileInputStream(file).use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashedBytes = digest.digest()
        return hashedBytes.joinToString("") { String.format("%02x", it) }
    }
}
