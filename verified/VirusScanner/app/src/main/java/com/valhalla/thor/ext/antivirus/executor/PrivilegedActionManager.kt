package com.valhalla.thor.ext.antivirus.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast

class PrivilegedActionManager(private val context: Context) {

    companion object {
        private const val TAG = "ThorAntivirusExecutor"
    }

    /**
     * Silent Uninstall utilizes privileged `pm uninstall` commands.
     * Guardrail/Downgrade: If executorBinder is null or command execution fails (e.g. Shizuku is dead),
     * we gracefully downgrade to standard, interactive ACTION_DELETE uninstallation flow.
     */
    fun executeUninstall(packageName: String, executorBinder: IBinder?): Boolean {
        Log.i(TAG, "Initiating uninstall for package: $packageName")
        
        if (executorBinder != null) {
            val command = "pm uninstall $packageName"
            val success = runPrivilegedCommand(command, executorBinder)
            if (success) {
                Log.i(TAG, "Privileged silent uninstall succeeded for $packageName")
                return true
            }
            Log.w(TAG, "Privileged silent uninstall failed, falling back to standard interactive manager...")
        } else {
            Log.w(TAG, "Privileged session unavailable (Shizuku/Root disconnected), falling back to standard interactive manager...")
        }
        
        // Graceful Downgrade: Trigger system interactive delete dialog
        return try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Standard uninstallation fallback failed", e)
            false
        }
    }

    /**
     * Silent Disable/Freeze completely restricts package execution on User 0.
     * Guardrail/Downgrade: Standard non-root/non-Shizuku apps CANNOT freeze other apps.
     * Therefore, we show a helpful Toast warning to instruct the user.
     */
    fun executeFreeze(packageName: String, executorBinder: IBinder?): Boolean {
        Log.i(TAG, "Initiating freeze for package: $packageName")
        
        if (executorBinder != null) {
            val command = "pm disable-user --user 0 $packageName"
            val success = runPrivilegedCommand(command, executorBinder)
            if (success) {
                Log.i(TAG, "Privileged silent freeze succeeded for $packageName")
                return true
            }
        }
        
        // Display user-friendly explanation toast since freezing is strictly privileged
        Toast.makeText(
            context,
            "Shizuku or Root access is currently unavailable. App freezing requires elevated privileges.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    /**
     * Silent Grant uses privileged `pm grant` commands to automatically authorize permission requests.
     */
    fun executePrivilegedGrant(packageName: String, permission: String, executorBinder: IBinder?): Boolean {
        Log.i(TAG, "Attempting privileged grant for $permission to $packageName")
        if (executorBinder != null) {
            val command = "pm grant $packageName $permission"
            val success = runPrivilegedCommand(command, executorBinder)
            if (success) {
                Log.i(TAG, "Privileged permission grant succeeded for $permission")
                return true
            }
        }
        return false
    }

    private fun runPrivilegedCommand(command: String, binder: IBinder): Boolean {
        return try {
            // Obtain parcel for Thor's IPC mechanism (AIDL-backed ShellExecutor proxy)
            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken("com.valhalla.thor.core.IShellExecutor")
                data.writeString(command)
                
                // Transaction ID corresponds to running shell command on Thor Core
                val transactionId = IBinder.FIRST_CALL_TRANSACTION + 1
                val status = binder.transact(transactionId, data, reply, 0)
                
                if (status) {
                    reply.readException()
                    val exitCode = reply.readInt()
                    val output = reply.readString()
                    Log.d(TAG, "Command output (Exit Code: $exitCode): $output")
                    exitCode == 0
                } else {
                    Log.e(TAG, "IPC transaction failed for shell command execution.")
                    false
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            false
        }
    }
}
