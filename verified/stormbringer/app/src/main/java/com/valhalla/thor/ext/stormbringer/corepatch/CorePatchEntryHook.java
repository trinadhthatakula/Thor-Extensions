/*
 * Stormbringer — CorePatch signature/digest bypass hooks (lifted & rewired).
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
package com.valhalla.thor.ext.stormbringer.corepatch;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The single COARSE per-install refresh point.
 *
 * <p>Runs inside {@code system_server}. Hooks ONE PMS install-entry method that fires once per install
 * and whose entry precedes any {@code synchronized(mLock)} in the body, then does exactly ONE thing:
 * refresh the cached GLOBAL CorePatch master-flag state
 * ({@link CorePatchConfig#refreshBlockingBounded()}) so the fine sig/digest hooks read a fresh flag
 * for this install.
 *
 * <p>Under the GLOBAL master-flag model this hook no longer inspects the target package, signer or
 * installer UID, mints no per-thread {@code ArmToken}, and matches nothing — the retired per-install
 * arm-state / thread-token model is gone. Whether the fine hooks fire is decided solely by
 * {@link CorePatchConfig#isEffectivelyEnabled()} reading the just-refreshed cache.
 *
 * <h3>Chosen hook site</h3>
 * Primary {@code InstallPackageHelper#installPackagesTraced} (Android 14/15/16); fallbacks
 * {@code PackageManagerService#installStage} then the {@code VerifyingSession} constructor. Each entry
 * is BEFORE the verify lock, so the bounded (800 ms) blocking IPC in {@code refreshBlockingBounded()}
 * runs off the PMS/verify lock. If none resolve, nothing is hooked and the cache simply keeps its last
 * (fail-safe) value.
 *
 * <h3>Fail-safe</h3>
 * EVERYTHING is wrapped in {@code try/catch(Throwable)} and never rethrows into {@code system_server}
 * (a throw here = bootloop). Any missing symbol / null / exception ⇒ no refresh ⇒ the fine hooks read
 * the previously cached (fail-safe DISABLED on first failure) state and verification proceeds.
 */
public class CorePatchEntryHook {

    /**
     * Install the coarse refresh hook onto the given (system_server) ClassLoader. Fail-safe: never
     * throws. If no candidate resolves, nothing is hooked and the cache is untouched.
     */
    public void install(ClassLoader cl) {
        try {
            XC_MethodHook cb = new CoarseRefreshHook();
            boolean hooked =
                    hookMethodByName(cl, "com.android.server.pm.InstallPackageHelper",
                            "installPackagesTraced", cb);
            if (!hooked) {
                hooked = hookMethodByName(cl, "com.android.server.pm.PackageManagerService",
                        "installStage", cb);
            }
            if (!hooked) {
                hookConstructors(cl, "com.android.server.pm.VerifyingSession", cb);
            }
        } catch (Throwable ignored) {
            // Fail-safe: any failure ⇒ no hook installed ⇒ verification fully intact.
        }
    }

    // ---- Hook resolution (returns whether at least one member was actually hooked) ----

    private static boolean hookMethodByName(ClassLoader cl, String className, String methodName,
                                            XC_MethodHook cb) {
        try {
            Class<?> clazz = XposedHelper.findClass(className, cl);
            if (clazz == null) return false;
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(clazz, methodName, cb);
            return unhooks != null && !unhooks.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hookConstructors(ClassLoader cl, String className, XC_MethodHook cb) {
        try {
            Class<?> clazz = XposedHelper.findClass(className, cl);
            if (clazz == null) return false;
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllConstructors(clazz, cb);
            return unhooks != null && !unhooks.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- The one coarse callback: refresh the cached GLOBAL state, off the verify lock ----

    private static final class CoarseRefreshHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                // Per-install refresh — OFF the PMS/verify lock (method entry precedes mLock).
                CorePatchConfig.INSTANCE.refreshBlockingBounded();
            } catch (Throwable ignored) {
                // never rethrow into system_server (a throw here = bootloop)
            }
        }
    }
}
