/*
 * Strombringer — CorePatch signature/digest bypass hooks (lifted & rewired).
 *
 * Derived from CorePatch (https://github.com/LSPosed/CorePatch) by the CorePatch
 * authors (weishu, yujincheng08 / canyie and the LSPosed contributors).
 * Original hook logic Copyright (C) the CorePatch authors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the Free
 * Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 */
package com.valhalla.thor.ext.strombringer.corepatch;

import android.os.Build;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Task 17 — the single SDK-dispatched CorePatch entry that runs inside
 * {@code system_server}. Task 18's {@code XposedEntry} gates on
 * {@code lpp.packageName == "android"} and then calls
 * {@code new CorePatchHook(lpp).start()}; this class assumes it is already in
 * system_server and does not re-check the process.
 *
 * <p>{@link #start()} installs two independent things:
 * <ol>
 *   <li>the <b>coarse</b> per-install refresh hook ({@link CorePatchEntryHook}),
 *       which refreshes the cached GLOBAL CorePatch master-flag state, and</li>
 *   <li>the version-matched <b>fine</b> sig/digest hooks
 *       ({@code CorePatchForQ..V}), selected by {@link Build.VERSION#SDK_INT}
 *       mirroring CorePatch's {@code MainHook} switch.</li>
 * </ol>
 *
 * <h3>Fail-safe contract</h3>
 * The coarse install and the fine dispatch each run in their <b>own</b>
 * try/catch so a failure in one cannot abort the other, and no throw can escape
 * {@code start()} into system_server (a bootloop-class failure). If a hook fails
 * to resolve, real verification simply runs unmodified for that path.
 *
 * <h3>What this deliberately does NOT do</h3>
 * It never calls {@code CorePatchConfig.refreshBlockingBounded()} at boot. The
 * refresh happens per-install inside the coarse hook; at boot the cache holds its
 * fail-safe DISABLED default, so every fine hook is a no-op until CorePatch is
 * effectively enabled (see {@link CorePatchConfig#isEffectivelyEnabled()}).
 */
public class CorePatchHook {

    private static final String TAG = "Strombringer/CorePatch";

    private final XC_LoadPackage.LoadPackageParam lpp;

    public CorePatchHook(XC_LoadPackage.LoadPackageParam lpp) {
        this.lpp = lpp;
    }

    /**
     * Installs the coarse entry hook and the version-matched fine hooks. Fail-safe:
     * any throw is swallowed and logged; the two installs are independent.
     */
    public void start() {
        // (1) Coarse per-install decision hook — its OWN try/catch so a failure
        //     here cannot prevent the fine hooks from installing.
        try {
            new CorePatchEntryHook().install(lpp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("E/" + TAG + " coarse entry hook install failed");
            XposedBridge.log(t);
        }

        // (2) Version-matched fine sig/digest hooks — its OWN try/catch, fully
        //     separate from the coarse install above. Refresh is NOT called here;
        //     the fine hooks read the cached global master-flag state, which the
        //     coarse hook refreshes per-install. No arm-state / token.
        try {
            installFineHooks();
        } catch (Throwable t) {
            XposedBridge.log("E/" + TAG + " fine sig/digest hook install failed for SDK "
                    + Build.VERSION.SDK_INT);
            XposedBridge.log(t);
        }
    }

    /**
     * Dispatches to the version-matched fine hook, mirroring CorePatch's MainHook
     * SDK_INT switch. Unknown-newer SDKs fall back to the latest (V) hooks.
     */
    private void installFineHooks() throws Throwable {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.BAKLAVA:            // 36
            case Build.VERSION_CODES.VANILLA_ICE_CREAM:  // 35
                new CorePatchForV().handleLoadPackage(lpp);
                break;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE:   // 34
                new CorePatchForU().handleLoadPackage(lpp);
                break;
            case Build.VERSION_CODES.TIRAMISU:           // 33
                new CorePatchForT().handleLoadPackage(lpp);
                break;
            case Build.VERSION_CODES.S_V2:               // 32
            case Build.VERSION_CODES.S:                  // 31
                new CorePatchForS().handleLoadPackage(lpp);
                break;
            case Build.VERSION_CODES.R:                  // 30
                new CorePatchForR().handleLoadPackage(lpp);
                break;
            case Build.VERSION_CODES.Q:                  // 29
            case Build.VERSION_CODES.P:                  // 28
                new CorePatchForQ().handleLoadPackage(lpp);
                break;
            default:
                XposedBridge.log("W/" + TAG + " unsupported Android SDK "
                        + Build.VERSION.SDK_INT + " — falling back to latest (V) hooks");
                new CorePatchForV().handleLoadPackage(lpp);
                break;
        }
    }
}
