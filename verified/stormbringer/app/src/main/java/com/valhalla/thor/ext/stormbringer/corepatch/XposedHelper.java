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
 *
 * Lifted into Stormbringer and rewired off XSharedPreferences onto a
 * thread-scoped capability token (see CorePatchConfig). Hooks Thor does not need
 * (downgrade, shared-UID, hidden-API-whitelist, OEM, verification-agent) were
 * stripped. The hardcoded platform-cert fallback was dropped.
 */
package com.valhalla.thor.ext.stormbringer.corepatch;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class XposedHelper {
    /** Log tag (replaces the un-lifted MainHook.TAG). */
    public static final String TAG = "Stormbringer/CorePatch";

    /** Debug logging is compiled out — never log from a system_server hook in release. */
    protected static final boolean DEBUG = false;

    /** Prefix of the single per-fire "Stormbringer log" line emitted when a bypass actually fires. */
    private static final String BYPASS_LOG_PREFIX = "Stormbringer[corepatch] bypassed ";

    /**
     * The single GLOBAL gate that replaces the retired per-thread capability token. A fine sig/digest
     * hook fires only when CorePatch is effectively enabled (master flag + auto-off window, see
     * {@link CorePatchConfig#isEffectivelyEnabled()}) — ONE flag drives both the sig and digest
     * clusters. On {@code true} it emits ONE {@link #logBypass} line naming [hookName]. Use this where
     * a {@code true} result immediately drives the {@code setResult}; where the gate is only one of
     * several conditions, gate on {@link CorePatchConfig#isEffectivelyEnabled()} directly (cheap, no
     * log) and call {@link #logBypass} at the actual {@code setResult}. Fail-safe: any throw ⇒ false ⇒
     * real verification proceeds untouched.
     */
    public static boolean bypass(String hookName) {
        try {
            if (CorePatchConfig.INSTANCE.isEffectivelyEnabled()) {
                logBypass(hookName);
                return true;
            }
        } catch (Throwable ignored) {
            // never rethrow into system_server
        }
        return false;
    }

    /** Emits the single Stormbringer bypass log line for [hookName]. Fail-safe; call only on a fire. */
    public static void logBypass(String hookName) {
        try {
            XposedBridge.log(BYPASS_LOG_PREFIX + hookName);
        } catch (Throwable ignored) {
            // never rethrow into system_server
        }
    }

    public static void findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        try {
            if (findClass(className, classLoader) != null) {
                XposedHelpers.findAndHookMethod(className, classLoader, methodName, parameterTypesAndCallback);
            }
        } catch (Throwable e) {
            if (DEBUG) XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(e));
        }
    }

    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        try {
            if (clazz != null) {
                XposedHelpers.findAndHookMethod(clazz, methodName, parameterTypesAndCallback);
            }
        } catch (Throwable e) {
            if (DEBUG) XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(e));
        }
    }

    public static void hookAllMethods(String className, ClassLoader classLoader, String methodName, XC_MethodHook callback) {
        try {
            Class<?> clazz = findClass(className, classLoader);
            if (clazz != null) {
                XposedBridge.hookAllMethods(clazz, methodName, callback);
            }
        } catch (Throwable e) {
            if (DEBUG) XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(e));
        }
    }

    public void hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        try {
            if (hookClass != null) {
                XposedBridge.hookAllMethods(hookClass, methodName, callback);
            }
        } catch (Throwable e) {
            if (DEBUG) XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(e));
        }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable e) {
            if (DEBUG) XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(e));
        }
        return null;
    }
}
