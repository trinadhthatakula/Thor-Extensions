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
 * T (API 33). Kept: checkCapability (sig, retargeted onto android.content.pm
 * .SigningDetails), assertMinSignatureSchemeIsValid + StrictJarVerifier rollback
 * flag + verity (digest). Stripped: doesSignatureMatchForPermissions (permission),
 * canJoinSharedUserId deopt (shared-UID) and the debug/shared-UID helpers.
 */
package com.valhalla.thor.ext.stormbringer.corepatch;

import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CorePatchForT extends CorePatchForS {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        super.handleLoadPackage(loadPackageParam);

        Class<?> signingDetails = getSigningDetails(loadPackageParam.classLoader);
        // New package has a different signature —— 处理覆盖安装但签名不一致.
        // (Retargets checkCapability onto T's android.content.pm.SigningDetails; overlaps with the
        // install already done in CorePatchForR via the overridden getSigningDetails() — idempotent.)
        hookAllMethods(signingDetails, "checkCapability", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // Carve-out: never grant PERMISSION (4) or AUTH (16). Gate (bypass) evaluated
                    // last so the log fires only on the actual bypass, never the carve-out no-op.
                    if ((Integer) param.args[1] != 4 && (Integer) param.args[1] != 16 && bypass("checkCapability")) {
                        param.setResult(true);
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        var assertMinSignatureSchemeIsValid = XposedHelpers.findMethodExactIfExists("com.android.server.pm.ScanPackageUtils", loadPackageParam.classLoader,
                "assertMinSignatureSchemeIsValid",
                "com.android.server.pm.parsing.pkg.AndroidPackage", int.class);
        if (assertMinSignatureSchemeIsValid != null) {
            XposedBridge.hookMethod(assertMinSignatureSchemeIsValid, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (bypass("assertMinSignatureSchemeIsValid")) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        Class<?> strictJarVerifier = findClass("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader);
        if (strictJarVerifier != null) {
            XposedBridge.hookAllConstructors(strictJarVerifier, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (bypass("StrictJarVerifier.rollbackProtections")) {
                            XposedHelpers.setBooleanField(param.thisObject, "signatureSchemeRollbackProtectionsEnforced", false);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        var apkSigningBlockClass = findClass("android.util.apk.ApkSigningBlockUtils", loadPackageParam.classLoader);
        var signatureInfoClass = findClass("android.util.apk.SignatureInfo", loadPackageParam.classLoader);
        findAndHookMethod(apkSigningBlockClass, "parseVerityDigestAndVerifySourceLength", byte[].class, long.class, signatureInfoClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (bypass("parseVerityDigestAndVerifySourceLength")) {
                        param.setResult(Arrays.copyOfRange((byte[]) param.args[0], 0, 32));
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        findAndHookMethod(apkSigningBlockClass, "verifyIntegrityForVerityBasedAlgorithm", byte[].class, RandomAccessFile.class, signatureInfoClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (bypass("verifyIntegrityForVerityBasedAlgorithm")) {
                        param.setResult(null);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    Class<?> getSigningDetails(ClassLoader classLoader) {
        return XposedHelpers.findClassIfExists("android.content.pm.SigningDetails", classLoader);
    }
}
