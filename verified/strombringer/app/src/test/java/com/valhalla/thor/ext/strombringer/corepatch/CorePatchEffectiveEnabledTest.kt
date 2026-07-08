package com.valhalla.thor.ext.strombringer.corepatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `effectiveEnabled(...)` is the GLOBAL CorePatch master gate — it decides whether the sig/digest
 * bypass is live for ALL installs. It is PURE (takes [nowElapsed] as an argument, reads no clock and
 * makes no Android call) so it runs on a plain JVM. All times are in the
 * `SystemClock.elapsedRealtime` (boot) domain.
 */
class CorePatchEffectiveEnabledTest {

    // A 20-minute auto-off window is 1_200_000 ms.
    private val window20mMs = 20L * 60_000L

    @Test fun `enabled with auto-off, inside window is on`() =
        assertTrue(
            CorePatchConfig.effectiveEnabled(
                enabled = true, autoOffEnabled = true, autoOffMinutes = 20,
                enabledAt = 1_000L, nowElapsed = 1_000L + 10L * 60_000L,
            ),
        )

    @Test fun `enabled with auto-off, at the exact window edge is on`() =
        assertTrue(
            CorePatchConfig.effectiveEnabled(
                enabled = true, autoOffEnabled = true, autoOffMinutes = 20,
                enabledAt = 1_000L, nowElapsed = 1_000L + window20mMs,
            ),
        )

    @Test fun `enabled with auto-off, past window is off`() =
        assertFalse(
            CorePatchConfig.effectiveEnabled(
                enabled = true, autoOffEnabled = true, autoOffMinutes = 20,
                enabledAt = 1_000L, nowElapsed = 1_000L + window20mMs + 1L,
            ),
        )

    @Test fun `auto-off disabled and enabled is on at any now`() =
        assertTrue(
            CorePatchConfig.effectiveEnabled(
                enabled = true, autoOffEnabled = false, autoOffMinutes = 20,
                enabledAt = 1_000L, nowElapsed = 999_999_999L,
            ),
        )

    @Test fun `enabledAt zero is off`() =
        assertFalse(
            CorePatchConfig.effectiveEnabled(
                enabled = true, autoOffEnabled = true, autoOffMinutes = 20,
                enabledAt = 0L, nowElapsed = 5_000L,
            ),
        )

    // After a reboot elapsedRealtime resets, so now < the pre-reboot stamp ⇒ negative delta ⇒ off.
    @Test fun `after reboot now less than enabledAt is off`() =
        assertFalse(
            CorePatchConfig.effectiveEnabled(
                enabled = true, autoOffEnabled = true, autoOffMinutes = 20,
                enabledAt = 500_000L, nowElapsed = 1_000L,
            ),
        )

    @Test fun `master flag off is off`() =
        assertFalse(
            CorePatchConfig.effectiveEnabled(
                enabled = false, autoOffEnabled = true, autoOffMinutes = 20,
                enabledAt = 1_000L, nowElapsed = 1_500L,
            ),
        )
}
