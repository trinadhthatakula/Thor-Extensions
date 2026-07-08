@file:Suppress("unused")

package com.valhalla.thor.ext.strombringer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.components.AsgardHeader
import com.valhalla.asgard.components.AsgardLabeledSlider
import com.valhalla.asgard.components.AsgardSettingToggleRow
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.extension.api.ExtensionDataStore
import com.valhalla.thor.extension.api.ShellExecutor
import kotlin.math.roundToInt

/**
 * Thor-side surface for Strombringer (renders IN Thor's process). Reads/writes flags through
 * Strombringer's config provider over IPC. The key travels as the `arg` of the provider call.
 */
class StrombringerExtension : AutomationExtension {
    override val name = "Strombringer"
    override val description = "Auto-unfreeze suspended apps on launch (requires LSPosed)."
    override val version = "1.00.0"
    override val author = "Thor"

    override suspend fun onTrigger(
        context: Context, eventType: String,
        shellExecutor: ShellExecutor, dataStore: ExtensionDataStore
    ) {
        // No event-driven behavior — Strombringer acts through its LSPosed hooks.
    }

    @Composable
    override fun ConfigurationScreen(
        shellExecutor: ShellExecutor, dataStore: ExtensionDataStore, onBack: () -> Unit
    ) {
        val ctx = LocalContext.current
        val uri = Uri.parse("content://" + Config.AUTHORITY)

        var autoUnfreeze by remember {
            mutableStateOf(getBool(ctx, uri, "get", Config.KEY_AUTO_UNFREEZE))
        }
        // Seed the whole CorePatch group (enabled + auto-off settings) from one authoritative read.
        val corePatchState = remember { getCorePatchState(ctx, uri) }
        var corePatch by remember { mutableStateOf(corePatchState.enabled) }
        var autoOff by remember { mutableStateOf(corePatchState.autoOffEnabled) }
        var autoOffMinutes by remember { mutableIntStateOf(corePatchState.autoOffMinutes) }
        var showCorePatchConfirm by remember { mutableStateOf(false) }

        Column {
            AsgardHeader(title = "Strombringer", onNavigateBack = onBack)
            AsgardSettingToggleRow(
                title = "Auto-unfreeze on launch",
                subtitle = "Tap a suspended app's icon to unsuspend and open it.",
                checked = autoUnfreeze,
                onCheckedChange = { v ->
                    autoUnfreeze = v; setBool(ctx, uri, "set", Config.KEY_AUTO_UNFREEZE, v)
                },
                modifier = Modifier.padding(16.dp)
            )
            AsgardSettingToggleRow(
                title = "CorePatch (signature-bypass) — danger zone",
                subtitle = "Disables Android's signature/tamper protection for installs Thor performs. " +
                        "Requires Root + the Strombringer LSPosed module active.",
                checked = corePatch,
                onCheckedChange = { v ->
                    if (v) {
                        // Enabling is destructive — gate behind type-to-confirm.
                        showCorePatchConfirm = true
                    } else {
                        // Kill-switch: disabling takes effect immediately, no confirmation.
                        corePatch = false; setBool(ctx, uri, "set", Config.KEY_CORE_PATCH_ENABLED, false)
                    }
                },
                modifier = Modifier.padding(16.dp)
            )

            // Auto-off controls only make sense while CorePatch is globally on.
            if (corePatch) {
                AsgardSettingToggleRow(
                    title = "Auto-off",
                    subtitle = "Turn CorePatch back off automatically after a set time, and never let " +
                            "it survive a reboot. Turn this off to keep CorePatch on until you disable " +
                            "it manually.",
                    checked = autoOff,
                    onCheckedChange = { v ->
                        autoOff = v; setBool(ctx, uri, "set", Config.KEY_AUTO_OFF_ENABLED, v)
                    },
                    modifier = Modifier.padding(16.dp)
                )
                if (autoOff) {
                    AsgardLabeledSlider(
                        label = "Auto-off after",
                        value = autoOffMinutes.toFloat(),
                        onValueChange = { f ->
                            autoOffMinutes = f.roundToInt()
                                .coerceIn(Config.AUTO_OFF_MIN_MINUTES, Config.AUTO_OFF_MAX_MINUTES)
                        },
                        // Persist only when the drag ends — avoids an IPC per frame.
                        onValueChangeFinished = {
                            setInt(ctx, uri, "setInt", Config.KEY_AUTO_OFF_MINUTES, autoOffMinutes)
                        },
                        valueRange = Config.AUTO_OFF_MIN_MINUTES.toFloat()..
                                Config.AUTO_OFF_MAX_MINUTES.toFloat(),
                        valueLabel = "$autoOffMinutes min",
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        if (showCorePatchConfirm) {
            CorePatchConfirmDialog(
                onDismiss = { showCorePatchConfirm = false },
                onConfirm = {
                    showCorePatchConfirm = false
                    corePatch = true
                    setBool(ctx, uri, "set", Config.KEY_CORE_PATCH_ENABLED, true)
                }
            )
        }
    }

    private fun getBool(ctx: Context, uri: Uri, method: String, key: String): Boolean = runCatching {
        ctx.contentResolver.call(uri, method, key, null)?.getBoolean("value") == true
    }.getOrDefault(false)

    private fun setBool(ctx: Context, uri: Uri, method: String, key: String, value: Boolean) {
        runCatching {
            ctx.contentResolver.call(uri, method, key, Bundle().apply { putBoolean("value", value) })
        }
    }

    private fun setInt(ctx: Context, uri: Uri, method: String, key: String, value: Int) {
        runCatching {
            ctx.contentResolver.call(uri, method, key, Bundle().apply { putInt("value", value) })
        }
    }

    /** Single-call read of the whole CorePatch group, applying the provider's defaults. */
    private fun getCorePatchState(ctx: Context, uri: Uri): CorePatchState = runCatching {
        val b = ctx.contentResolver.call(uri, "getCorePatchState", null, null)
        CorePatchState(
            enabled = b?.getBoolean("enabled") == true,
            autoOffEnabled = b?.getBoolean("auto_off_enabled", Config.DEFAULT_AUTO_OFF_ENABLED)
                ?: Config.DEFAULT_AUTO_OFF_ENABLED,
            autoOffMinutes = b?.getInt("auto_off_minutes", Config.DEFAULT_AUTO_OFF_MINUTES)
                ?: Config.DEFAULT_AUTO_OFF_MINUTES,
        )
    }.getOrDefault(
        CorePatchState(false, Config.DEFAULT_AUTO_OFF_ENABLED, Config.DEFAULT_AUTO_OFF_MINUTES),
    )
}

/** UI-side view of the CorePatch group (enabled_at is provider-managed, not needed by the screen). */
private data class CorePatchState(
    val enabled: Boolean,
    val autoOffEnabled: Boolean,
    val autoOffMinutes: Int,
)

private const val CORE_PATCH_CONFIRM_PHRASE = "I understand the risk"

@Composable
private fun CorePatchConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val matches = typed.trim() == CORE_PATCH_CONFIRM_PHRASE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable CorePatch signature bypass?") },
        text = {
            Column {
                Text(
                    "This disables Android's signature, tamper, and impersonation protection for " +
                            "installs Thor performs. Malware can then masquerade as trusted apps and " +
                            "silently update them.\n\n" +
                            "Requires Root and the Strombringer LSPosed module active.\n\n" +
                            "Thor is NOT responsible for any resulting data loss, malware, or damage.\n\n" +
                            "Type \"$CORE_PATCH_CONFIRM_PHRASE\" to continue."
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = matches) { Text("Enable") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
