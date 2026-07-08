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
 *
 * Lifted into Strombringer and rewired off XSharedPreferences onto a
 * thread-scoped capability token (see CorePatchConfig). Standalone path for
 * API 28-29. Stripped: downgrade + version-code zeroing, hidden-API-whitelist,
 * verification-agent, and the verifyV1Signature fabrication (it relied solely on
 * the hardcoded platform cert we dropped).
 */
package com.valhalla.thor.ext.strombringer.corepatch;


import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CorePatchForQ extends XposedHelper implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        // ---- Digest cluster (gated on the GLOBAL master flag) ----
        hookAllMethods("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader, "verifyMessageDigest",
                new ReturnConstant("verifyMessageDigest", true));
        hookAllMethods("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader, "verify",
                new ReturnConstant("verify", true));
        hookAllMethods("java.security.MessageDigest", loadPackageParam.classLoader, "isEqual",
                new ReturnConstant("MessageDigest.isEqual", true));

        // ---- Signature cluster (gated on the GLOBAL master flag) ----
        hookAllMethods("com.android.server.pm.PackageManagerServiceUtils", loadPackageParam.classLoader, "verifySignatures",
                new ReturnConstant("verifySignatures", false));

        Class<?> signingDetails = XposedHelpers.findClass("android.content.pm.PackageParser.SigningDetails", loadPackageParam.classLoader);

        // New package has a different signature —— 处理覆盖安装但签名不一致
        hookAllMethods(signingDetails, "checkCapability", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // Carve-out: never grant PERMISSION (4) or AUTH (16). Gate (bypass) is evaluated
                    // last so the Strombringer log fires only on the actual bypass, never the carve-out.
                    if ((Integer) param.args[1] != 4 && (Integer) param.args[1] != 16 && bypass("checkCapability")) {
                        param.setResult(Boolean.TRUE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        hookAllMethods(signingDetails, "checkCapabilityRecover", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if ((Integer) param.args[1] != 4 && (Integer) param.args[1] != 16 && bypass("checkCapabilityRecover")) {
                        param.setResult(Boolean.TRUE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        var keySetManagerClass = findClass("com.android.server.pm.KeySetManagerService", loadPackageParam.classLoader);
        if (keySetManagerClass != null) {
            var shouldBypass = new ThreadLocal<Boolean>();
            hookAllMethods(keySetManagerClass, "shouldCheckUpgradeKeySetLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // Cheap global gate FIRST (short-circuits the stack walk when disabled); log
                        // only on the actual fire so the no-op path stays silent.
                        if (CorePatchConfig.INSTANCE.isEffectivelyEnabled() &&
                                Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch((o) ->
                                        (/* API 29 */ "preparePackageLI".equals(o.getMethodName()) || /* API 28 */ "installPackageLI".equals(o.getMethodName())))) {
                            shouldBypass.set(true);
                            param.setResult(true);
                            logBypass("shouldCheckUpgradeKeySetLocked");
                        } else {
                            shouldBypass.set(false);
                        }
                    } catch (Throwable ignored) {
                        shouldBypass.set(false);
                    }
                }
            });
            hookAllMethods(keySetManagerClass, "checkUpgradeKeySetLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (Boolean.TRUE.equals(shouldBypass.get()) && bypass("checkUpgradeKeySetLocked")) {
                            param.setResult(true);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        // Allow apk splits with different signatures to be installed together
        hookAllMethods(signingDetails, "signaturesMatchExactly", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (bypass("signaturesMatchExactly")) param.setResult(true);
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
