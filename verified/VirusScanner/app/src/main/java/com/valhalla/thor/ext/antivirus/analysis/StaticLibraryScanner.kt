package com.valhalla.thor.ext.antivirus.analysis

import java.io.File
import java.util.zip.ZipFile

data class LibraryDetectionResult(
    val hasLibSu: Boolean,
    val hasHiddenApiBypass: Boolean
)

class StaticLibraryScanner {

    fun detectLibrariesInApk(apkPath: String): LibraryDetectionResult {
        var hasLibSu = false
        var hasHiddenApiBypass = false
        
        try {
            val file = File(apkPath)
            if (!file.exists()) return LibraryDetectionResult(false, false)
            
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                        zip.getInputStream(entry).use { input ->
                            // Read chunk-by-chunk to be extremely memory safe and fast
                            val buffer = ByteArray(65536) // 64KB chunks
                            var bytesRead: Int
                            val targetLibSu = "Lcom/topjohnwu/superuser".toByteArray(Charsets.UTF_8)
                            val targetBypass = "Lorg/lsposed/hiddenapibypass".toByteArray(Charsets.UTF_8)
                            
                            // To handle boundary crossings, we keep a sliding buffer overlap
                            val slidingBuffer = ByteArray(131072) // 128KB total
                            var slidingOffset = 0
                            
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                System.arraycopy(buffer, 0, slidingBuffer, slidingOffset, bytesRead)
                                val scanLength = slidingOffset + bytesRead
                                
                                if (!hasLibSu && containsBytePattern(slidingBuffer, scanLength, targetLibSu)) {
                                    hasLibSu = true
                                }
                                if (!hasHiddenApiBypass && containsBytePattern(slidingBuffer, scanLength, targetBypass)) {
                                    hasHiddenApiBypass = true
                                }
                                
                                if (hasLibSu && hasHiddenApiBypass) break
                                
                                // Slide overlap back
                                val overlap = 512
                                if (scanLength > overlap) {
                                    System.arraycopy(slidingBuffer, scanLength - overlap, slidingBuffer, 0, overlap)
                                    slidingOffset = overlap
                                } else {
                                    slidingOffset = 0
                                }
                            }
                        }
                    }
                    if (hasLibSu && hasHiddenApiBypass) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return LibraryDetectionResult(hasLibSu, hasHiddenApiBypass)
    }

    private fun containsBytePattern(source: ByteArray, scanLength: Int, pattern: ByteArray): Boolean {
        if (pattern.size > scanLength) return false
        for (i in 0..scanLength - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (source[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }
}
