package com.valhalla.thor.ext.strombringer.corepatch

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.valhalla.thor.ext.strombringer.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A parsed snapshot of the extension's OWN GLOBAL CorePatch state (from `StrombringerConfigProvider`'s
 * `getCorePatchState`). [DISABLED] is the fail-safe value used on any timeout / null / exception.
 *
 * - [enabled] — the master toggle. When effectively enabled, EVERY install is signature-patched.
 * - [autoOffEnabled] — when true, the on-window is bounded by [autoOffMinutes] AND never survives a
 *   reboot (the stamp is boot-relative). When false, CorePatch stays on while [enabled].
 * - [autoOffMinutes] — minutes the window stays open after being enabled.
 * - [enabledAt] — a [SystemClock.elapsedRealtime] stamp captured when enabled (0 when off).
 */
data class CorePatchState(
    val enabled: Boolean,
    val autoOffEnabled: Boolean,
    val autoOffMinutes: Int,
    val enabledAt: Long,
) {
    companion object {
        /** Fail-safe DISABLED state: the master flag is off, so the hooks are inert. */
        val DISABLED = CorePatchState(false, true, Config.DEFAULT_AUTO_OFF_MINUTES, 0L)
    }
}

/**
 * Runs inside `system_server` (the CorePatch signature-bypass hook host). Reads the extension's OWN
 * GLOBAL CorePatch state over its exported config provider IPC and caches it; the fine sig/digest
 * hooks gate on [isEffectivelyEnabled]. This is now a single global master toggle — the retired
 * per-install arm-state / thread-token / signer model is gone.
 *
 * Hard rules:
 * - **Fail-safe:** any timeout / null / exception on any path ⇒ [CorePatchState.DISABLED] ⇒
 *   [isEffectivelyEnabled] returns false. [refreshBlockingBounded] never throws.
 * - **Never under a PMS lock.** The IPC is bounded (worker thread + 800ms cap) but is still blocking;
 *   only the coarse install-entry hook may call it, off-lock.
 * - **Boot (elapsedRealtime) clock only.** The provider stamps `enabled_at` with
 *   `SystemClock.elapsedRealtime()`; [effectiveEnabled] compares in that same domain so the on-window
 *   never survives a reboot.
 */
object CorePatchConfig {
    /** Hard cap for the whole bounded refresh. */
    private const val TIMEOUT_MILLIS = 800L

    /** Milliseconds per minute — the auto-off window unit. */
    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * The extension's OWN config-provider URI (NOT Thor's — Thor no longer serves any arm-state).
     * Lazy so the pure [effectiveEnabled] / object init never touches `Uri.parse` (an Android call);
     * it is only built on the first real IPC in [query].
     */
    private val CONFIG_URI: Uri by lazy { Uri.parse("content://${Config.AUTHORITY}") }

    /**
     * Last global state fetched by the coarse hook. `@Volatile` so a fine hook on any thread reads the
     * freshest snapshot. Fail-safe default: [CorePatchState.DISABLED].
     */
    @Volatile
    private var cached: CorePatchState = CorePatchState.DISABLED

    /**
     * Bounded, blocking fetch of the extension's OWN global CorePatch state via the config provider's
     * `getCorePatchState`. Runs the `.call` on a worker thread and waits at most [TIMEOUT_MILLIS]. On
     * timeout / exception / missing context / null response ⇒ caches [CorePatchState.DISABLED] (the
     * safe direction for a bypass gate). Never throws. NEVER call under a PMS lock.
     */
    fun refreshBlockingBounded(): CorePatchState {
        val ctx = systemContext()
        if (ctx == null) {
            cached = CorePatchState.DISABLED
            return CorePatchState.DISABLED
        }
        val executor = Executors.newSingleThreadExecutor()
        val state = try {
            val future = executor.submit(Callable { query(ctx) })
            try {
                future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: CorePatchState.DISABLED
            } catch (_: Throwable) {
                future.cancel(true)
                CorePatchState.DISABLED
            }
        } catch (_: Throwable) {
            CorePatchState.DISABLED
        } finally {
            runCatching { executor.shutdownNow() }
        }
        cached = state
        return state
    }

    /**
     * The GLOBAL effective-enabled predicate applied to the cached snapshot at the current boot clock.
     * Fail-safe: any error ⇒ false ⇒ real verification proceeds.
     */
    fun isEffectivelyEnabled(): Boolean = try {
        val s = cached
        effectiveEnabled(
            s.enabled,
            s.autoOffEnabled,
            s.autoOffMinutes,
            s.enabledAt,
            SystemClock.elapsedRealtime(),
        )
    } catch (_: Throwable) {
        false
    }

    /**
     * PURE effective-enabled predicate. Takes [nowElapsed] as an argument (never reads a clock) and
     * makes no Android calls, so it runs on a plain JVM.
     *
     * CorePatch is effective iff it is [enabled], has a valid on-stamp ([enabledAt] > 0), and either
     * auto-off is disabled (stays on while enabled) OR [nowElapsed] is within the on-window. All times
     * are in the [SystemClock.elapsedRealtime] (boot) domain: after a reboot elapsedRealtime resets, so
     * `nowElapsed < enabledAt` ⇒ a negative delta ⇒ outside `0..window` ⇒ off.
     */
    fun effectiveEnabled(
        enabled: Boolean,
        autoOffEnabled: Boolean,
        autoOffMinutes: Int,
        enabledAt: Long,
        nowElapsed: Long,
    ): Boolean =
        enabled &&
            enabledAt > 0 &&
            (!autoOffEnabled ||
                (nowElapsed - enabledAt) in 0..(autoOffMinutes.toLong() * MILLIS_PER_MINUTE))

    // --- internals ---

    private fun query(ctx: Context): CorePatchState {
        val bundle = ctx.contentResolver.call(CONFIG_URI, "getCorePatchState", null, null)
            ?: return CorePatchState.DISABLED
        return parse(bundle)
    }

    private fun parse(bundle: Bundle): CorePatchState = CorePatchState(
        enabled = bundle.getBoolean("enabled", false),
        autoOffEnabled = bundle.getBoolean("auto_off_enabled", true),
        autoOffMinutes = bundle.getInt("auto_off_minutes", Config.DEFAULT_AUTO_OFF_MINUTES),
        enabledAt = bundle.getLong("enabled_at", 0L),
    )

    /**
     * The `system_server` global context. NOT `p.thisObject` (that Context does not exist in
     * system_server). Any reflection failure ⇒ null ⇒ DISABLED.
     */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as Context
    }.getOrNull()
}
