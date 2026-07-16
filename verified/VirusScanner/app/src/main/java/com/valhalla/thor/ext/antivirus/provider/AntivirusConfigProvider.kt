package com.valhalla.thor.ext.antivirus.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.content.pm.PackageManager

class AntivirusConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.valhalla.thor.ext.antivirus.configprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")
        
        private const val CONFIG_MATCH = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "config", CONFIG_MATCH)
        }

        // Trusted package names of Thor Core (differing by local dev vs release build)
        private val TRUSTED_PACKAGES = setOf("com.valhalla.thor", "com.valhalla.thor.debug")
    }

    private var threatScanThreshold: Int = 3 // Threshold for malicious matches before alert

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        validateCallerOrThrow()
        
        return when (uriMatcher.match(uri)) {
            CONFIG_MATCH -> {
                val cursor = MatrixCursor(arrayOf("threat_threshold"))
                cursor.addRow(arrayOf(threatScanThreshold))
                cursor
            }
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        validateCallerOrThrow()
        if (uriMatcher.match(uri) == CONFIG_MATCH && values != null) {
            threatScanThreshold = values.getAsInteger("threat_threshold") ?: threatScanThreshold
            context?.contentResolver?.notifyChange(uri, null)
            return 1
        }
        return 0
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.com.valhalla.thor.ext.config"

    private fun validateCallerOrThrow() {
        val callingPkg = callingPackage ?: throw SecurityException("Caller package not identified.")
        val pm = context?.packageManager ?: throw IllegalStateException("PackageManager unavailable.")
        
        // 1. Dynamic Certificate Match: Check if caller's signature matches the extension's signature.
        val signatureMatch = pm.checkSignatures(callingPkg, context?.packageName ?: "") == PackageManager.SIGNATURE_MATCH
        if (signatureMatch) return

        // 2. Package Name Fallback: If signatures do not match
        if (callingPkg in TRUSTED_PACKAGES) {
            return
        }

        throw SecurityException("Access denied: Package $callingPkg has an invalid signature / authority match.")
    }
}
