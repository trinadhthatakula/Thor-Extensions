package com.valhalla.thor.ext.stormbringer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock

object Config {
    const val PREFS = "stormbringer_prefs"
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

    const val AUTHORITY = "com.valhalla.thor.ext.stormbringer.config"
    // Thor's applicationId differs by build (…​.debug in debug); callers try both.
    val THOR_PACKAGES = listOf("com.valhalla.thor", "com.valhalla.thor.debug")
}

/**
 * Config store for Stormbringer. Stormbringer's own ConfigActivity (in this app's process) writes
 * here via contentResolver.call; the launcher hook (in the launcher's process) and the system_server
 * CorePatch hook read here the same way. Backed by PRIVATE prefs — the cross-process reach is the
 * exported provider IPC. The launcher/system_server are system callers, so they can resolve this
 * provider despite Android 11+ package visibility.
 */
class StormbringerConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        val prefs = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val out = Bundle()
        // The key is the `arg`. Defaults to KEY_AUTO_UNFREEZE so the legacy launcher-hook caller
        // (which passes arg=null) keeps READING the auto-unfreeze flag unchanged; writes are now
        // restricted to Stormbringer's own process (see requireOwnProcessWriter).
        val key = arg ?: Config.KEY_AUTO_UNFREEZE
        when (method) {
            "set" -> {
                requireOwnProcessWriter()
                // Only honour a supplied "value"; a missing key returns false from getBoolean and
                // would silently clobber the real setting, so fall back to the key's default instead.
                val value = if (extras?.containsKey("value") == true) {
                    extras.getBoolean("value")
                } else {
                    boolDefault(key)
                }
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
            "setInt" -> {
                requireOwnProcessWriter()
                // getInt returns 0 for a MISSING key, so only write when "value" was actually
                // supplied; otherwise keep the stored value by writing the key's own default.
                val value = if (extras?.containsKey("value") == true) {
                    extras.getInt("value")
                } else {
                    intDefault(key)
                }
                prefs.edit().putInt(key, value).apply()
            }
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

    /**
     * Writes here are privileged: `core_patch_enabled` arms a GLOBAL install-time signature bypass
     * and `auto_unfreeze` changes launch behaviour, so an arbitrary app must never be able to flip
     * them. The ONLY legitimate writer is Stormbringer's own config UI, which runs in THIS process.
     *
     * We gate WRITES on the KERNEL-attested calling UID, NOT getCallingPackage(): that package is
     * derived from the caller-supplied AttributionSource and can be null/absent for a cross-process
     * caller, so a package check that admits a null caller (to let the same-process UI through) would
     * also admit a crafted cross-process call with no attribution — a full bypass of this gate on an
     * exported, permission-less provider. Binder.getCallingUid() cannot be spoofed or nulled: a
     * genuine same-process call (our own ConfigActivity, no inbound binder transaction) reports
     * Process.myUid(); every cross-process caller reports a different UID.
     *
     * READS stay OPEN because the launcher hook and the system_server CorePatch hook legitimately
     * read this provider yet are neither Thor nor us (and reads only reveal whether a flag is on;
     * Android 11+ package visibility already blocks arbitrary apps from resolving the provider).
     */
    private fun requireOwnProcessWriter() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Unauthorized writer uid=${Binder.getCallingUid()}")
        }
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
