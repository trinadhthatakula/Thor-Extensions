package com.valhalla.thor.ext.strombringer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.asgard.components.AsgardLabeledSlider
import com.valhalla.asgard.components.AsgardSettingToggleRow
import kotlin.math.roundToInt

/**
 * Strombringer's configuration UI, presented as a translucent bottom sheet (the manifest theme is
 * Theme.Translucent.NoTitleBar, mirroring Thor's PortableInstaller) so it slides up over a scrim
 * and reads as part of Thor rather than a mismatched full-screen page.
 *
 * It runs in the EXTENSION'S OWN process (Thor starts it via the CONFIGURE intent), NOT inside Thor.
 * That isolation is deliberate: a config screen rendered inside Thor would have to link, at runtime,
 * against Thor's minified Compose/Asgard/kotlin-stdlib (R8-stripped, parent-first classloading),
 * which is unwinnable across an independently-built plugin boundary. Here the Activity has its own
 * full Compose/Asgard, so nothing crosses the boundary. All state travels over Strombringer's own
 * config provider IPC.
 */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                StrombringerConfigSheet(onDismiss = { finish() })
            }
        }
    }
}

/** UI-side view of the CorePatch group (enabled_at is provider-managed, not needed by the screen). */
private data class CorePatchState(
    val enabled: Boolean,
    val autoOffEnabled: Boolean,
    val autoOffMinutes: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrombringerConfigSheet(onDismiss: () -> Unit) {
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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "STROMBRINGER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            AsgardSettingToggleRow(
                title = "Auto-unfreeze on launch",
                subtitle = "Tap a suspended app's icon to unsuspend and open it.",
                checked = autoUnfreeze,
                onCheckedChange = { v ->
                    autoUnfreeze = v; setBool(ctx, uri, "set", Config.KEY_AUTO_UNFREEZE, v)
                },
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
