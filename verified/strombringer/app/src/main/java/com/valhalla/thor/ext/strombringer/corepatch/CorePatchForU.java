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
 * U (API 34). Kept only: assertMinSignatureSchemeIsValid on the renamed
 * com.android.server.pm.pkg.AndroidPackage (digest). Stripped: reconcilePackages
 * deopt + ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS (shared-UID), checkDowngrade
 * (downgrade), and the Nothing NtConfigListServiceImpl OEM hooks.
 */
package com.valhalla.thor.ext.strombringer.corepatch;

import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CorePatchForU extends CorePatchForT {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        super.handleLoadPackage(loadPackageParam);

        // ee11a9c (Rename AndroidPackageApi to AndroidPackage)
        findAndHookMethod("com.android.server.pm.ScanPackageUtils", loadPackageParam.classLoader,
                "assertMinSignatureSchemeIsValid",
                "com.android.server.pm.pkg.AndroidPackage", int.class,
                new XC_MethodHook() {
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
}
