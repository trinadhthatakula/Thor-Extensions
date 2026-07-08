package com.valhalla.thor.ext.strombringer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock

object Config {
    const val PREFS = "strombringer_prefs"
    const val KEY_AUTO_UNFREEZE = "auto_unfreeze"
    const val KEY_CORE_PATCH_ENABLED = "core_patch_enabled"

    // --- GLOBAL CorePatch model: master toggle + auto-off timer ---
    // When CorePatch is on, EVERY install is signature-patched. Auto-off bounds that window:
    // CorePatch auto-disables after KEY_AUTO_OFF_MINUTES and never survives a reboot (the timer is
    // boot-relative, see KEY_ENABLED_AT). With auto-off off, it stays on until manually turned off.
    /** Boolean, default TRUE — auto-disable CorePatch after the timeout and never survive a reboot. */
    const val KEY_AUTO_OFF_ENABLED = "auto_off_enabled"
    /** Int minutes, default 20 — how long CorePatch stays on after being enabled. */
    const val KEY_AUTO_OFF_MINUTES = "auto_off_minutes"
    /**
     * Long — [SystemClock.elapsedRealtime] captured when CorePatch was turned on (0 when off). The
     * provider stamps/clears this authoritatively so the timer can't be forgotten by the UI. It is
     * boot-relative: after a reboot elapsedRealtime resets, so the on-window is naturally void.
     */
    const val KEY_ENABLED_AT = "core_patch_enabled_at"

    const val DEFAULT_AUTO_OFF_ENABLED = true
    const val DEFAULT_AUTO_OFF_MINUTES = 20
    const val AUTO_OFF_MIN_MINUTES = 1
    const val AUTO_OFF_MAX_MINUTES = 120

    const val AUTHORITY = "com.valhalla.thor.ext.strombringer.config"
    // Thor's applicationId differs by build (…​.debug in debug); callers try both.
    val THOR_PACKAGES = listOf("com.valhalla.thor", "com.valhalla.thor.debug")
}

/**
 * Config store for Strombringer. Strombringer's own ConfigActivity (in this app's process) writes
 * here via contentResolver.call; the launcher hook (in the launcher's process) and the system_server
 * CorePatch hook read here the same way. Backed by PRIVATE prefs — the cross-process reach is the
 * exported provider IPC. The launcher/system_server are system callers, so they can resolve this
 * provider despite Android 11+ package visibility.
 */
class StrombringerConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        val prefs = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val out = Bundle()
        // The key is the `arg`. Defaults to KEY_AUTO_UNFREEZE so the legacy launcher-hook caller
        // (which passes arg=null) keeps reading/writing the auto-unfreeze flag unchanged.
        val key = arg ?: Config.KEY_AUTO_UNFREEZE
        when (method) {
            "set" -> {
                val value = extras?.getBoolean("value") == true
                val editor = prefs.edit().putBoolean(key, value)
                // GLOBAL CorePatch: stamp/clear the auto-off timer authoritatively HERE so the UI can
                // never forget it. elapsedRealtime() shares the device boot clock with the
                // system_server hook, so the hook can compare windows consistently.
                if (key == Config.KEY_CORE_PATCH_ENABLED) {
                    editor.putLong(
                        Config.KEY_ENABLED_AT,
                        if (value) SystemClock.elapsedRealtime() else 0L,
                    )
                }
                editor.apply()
            }
            "get" -> out.putBoolean("value", prefs.getBoolean(key, boolDefault(key)))
            "setInt" -> prefs.edit().putInt(key, extras?.getInt("value") ?: intDefault(key)).apply()
            "getInt" -> out.putInt("value", prefs.getInt(key, intDefault(key)))
            // One-shot typed read for the M3 hook: everything it needs in a single IPC.
            "getCorePatchState" -> {
                out.putBoolean("enabled", prefs.getBoolean(Config.KEY_CORE_PATCH_ENABLED, false))
                out.putBoolean(
                    "auto_off_enabled",
                    prefs.getBoolean(Config.KEY_AUTO_OFF_ENABLED, Config.DEFAULT_AUTO_OFF_ENABLED),
                )
                out.putInt(
                    "auto_off_minutes",
                    prefs.getInt(Config.KEY_AUTO_OFF_MINUTES, Config.DEFAULT_AUTO_OFF_MINUTES),
                )
                out.putLong("enabled_at", prefs.getLong(Config.KEY_ENABLED_AT, 0L))
            }
        }
        return out
    }

    /** Per-key boolean default. Preserves legacy false for auto_unfreeze/core_patch_enabled. */
    private fun boolDefault(key: String): Boolean =
        key == Config.KEY_AUTO_OFF_ENABLED // only auto_off_enabled defaults to true

    /** Per-key int default. */
    private fun intDefault(key: String): Int = when (key) {
        Config.KEY_AUTO_OFF_MINUTES -> Config.DEFAULT_AUTO_OFF_MINUTES
        else -> 0
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
