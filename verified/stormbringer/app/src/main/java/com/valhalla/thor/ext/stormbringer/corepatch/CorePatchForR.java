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
 * thread-scoped capability token (see CorePatchConfig). Stripped: downgrade,
 * hidden-API-whitelist, shared-UID (hasCommonAncestor / SharedUserSetting /
 * canJoinSharedUserId), verification-agent (isVerificationEnabled), the debug
 * shell command, and the hardcoded platform-cert fallback.
 */
package com.valhalla.thor.ext.stormbringer.corepatch;


import android.annotation.TargetApi;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@TargetApi(Build.VERSION_CODES.R)
public class CorePatchForR extends XposedHelper implements IXposedHookLoadPackage {
    private final static Method deoptimizeMethod;

    static {
        Method m = null;
        try {
            m = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
        } catch (Throwable t) {
            XposedBridge.log("E/" + TAG + " " + Log.getStackTraceString(t));
        }
        deoptimizeMethod = m;
    }

    static void deoptimizeMethod(Class<?> c, String n) throws InvocationTargetException, IllegalAccessException {
        for (Method m : c.getDeclaredMethods()) {
            if (deoptimizeMethod != null && m.getName().equals(n)) {
                deoptimizeMethod.invoke(null, m);
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        // ---- Digest cluster (gated on the GLOBAL master flag) ----
        // apk 内文件修改后 digest 校验会失败
        hookAllMethods("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader, "verifyMessageDigest",
                new ReturnConstant("verifyMessageDigest", true));
        hookAllMethods("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader, "verify",
                new ReturnConstant("verify", true));
        hookAllMethods("java.security.MessageDigest", loadPackageParam.classLoader, "isEqual",
                new ReturnConstant("MessageDigest.isEqual", true));

        // target >= 30 的情况下 resources.arsc 必须是未压缩的且 4K 对齐
        hookAllMethods("android.content.res.AssetManager", loadPackageParam.classLoader, "containsAllocatedTable",
                new ReturnConstant("containsAllocatedTable", false));

        // No signature found in package of version <n> or newer for package <apk>
        findAndHookMethod("android.util.apk.ApkSignatureVerifier", loadPackageParam.classLoader, "getMinimumSignatureSchemeVersionForTargetSdk", int.class,
                new ReturnConstant("getMinimumSignatureSchemeVersionForTargetSdk", 0));
        var apkVerifierClass = XposedHelpers.findClassIfExists("com.android.apksig.ApkVerifier",
                loadPackageParam.classLoader);
        if (apkVerifierClass != null) {
            findAndHookMethod(apkVerifierClass, "getMinimumSignatureSchemeVersionForTargetSdk", int.class,
                    new ReturnConstant("ApkVerifier.getMinimumSignatureSchemeVersionForTargetSdk", 0));
        }

        // ---- Signature cluster (gated on the GLOBAL master flag) ----
        // Reflection handles used only by the verifyV1Signature fabrication path below.
        final Class<?> ASV = XposedHelpers.findClass("android.util.apk.ApkSignatureVerifier", loadPackageParam.classLoader);
        final Class<?> sJarClass = XposedHelpers.findClass("android.util.jar.StrictJarFile", loadPackageParam.classLoader);
        final Constructor<?> strictJarFileCtor = XposedHelpers.findConstructorExact(sJarClass, String.class, boolean.class, boolean.class);
        strictJarFileCtor.setAccessible(true);
        final Class<?> signingDetails = getSigningDetails(loadPackageParam.classLoader);
        final Constructor<?> signingDetailsCtor = XposedHelpers.findConstructorExact(signingDetails, Signature[].class, Integer.TYPE);
        signingDetailsCtor.setAccessible(true);
        final Class<?> packageParserException = XposedHelpers.findClass("android.content.pm.PackageParser.PackageParserException", loadPackageParam.classLoader);
        final Field error = XposedHelpers.findField(packageParserException, "error");
        error.setAccessible(true);
        final Class<?> parseResult = XposedHelpers.findClassIfExists("android.content.pm.parsing.result.ParseResult", loadPackageParam.classLoader);

        // 当 verifyV1Signature 抛出转换异常时，用从待安装 apk 中重新读取到的真实签名作为返回值。
        // 只使用从 apk 重新解析出的签名 —— 不再回退到硬编码的平台证书。若无法恢复真实签名则不介入。
        hookAllMethods("android.util.apk.ApkSignatureVerifier", loadPackageParam.classLoader, "verifyV1Signature", new XC_MethodHook() {
            public void afterHookedMethod(MethodHookParam methodHookParam) {
                try {
                    // Cheap global gate (no log — a passing gate here does NOT mean a bypass fired;
                    // the log is emitted below only when we actually setResult).
                    if (!CorePatchConfig.INSTANCE.isEffectivelyEnabled()) return;
                    Throwable throwable = methodHookParam.getThrowable();
                    Integer parseErr = null;
                    if (parseResult != null && ((Method) methodHookParam.method).getReturnType() == parseResult) {
                        Object result = methodHookParam.getResult();
                        if ((boolean) XposedHelpers.callMethod(result, "isError")) {
                            parseErr = (int) XposedHelpers.callMethod(result, "getErrorCode");
                        }
                    }
                    if (throwable == null && parseErr == null) return;

                    Signature[] lastSigs = null;
                    try {
                        final Object origJarFile = strictJarFileCtor.newInstance(methodHookParam.args[parseErr == null ? 0 : 1], true, false);
                        final ZipEntry manifestEntry = (ZipEntry) XposedHelpers.callMethod(origJarFile, "findEntry", "AndroidManifest.xml");
                        final Certificate[][] lastCerts;
                        if (parseErr != null) {
                            lastCerts = (Certificate[][]) XposedHelpers.callMethod(XposedHelpers.callStaticMethod(ASV, "loadCertificates", methodHookParam.args[0], origJarFile, manifestEntry), "getResult");
                        } else {
                            lastCerts = (Certificate[][]) XposedHelpers.callStaticMethod(ASV, "loadCertificates", origJarFile, manifestEntry);
                        }
                        lastSigs = (Signature[]) XposedHelpers.callStaticMethod(ASV, "convertToSignatures", (Object) lastCerts);
                    } catch (Throwable ignored) {
                    }

                    // No hardcoded platform-cert fallback: if we cannot recover the apk's real
                    // signatures, do nothing and let normal verification fail (fail-safe).
                    if (lastSigs == null) return;

                    Object newInstance = signingDetailsCtor.newInstance(new Object[]{lastSigs, 1});

                    // 修复 ClassCastException: PackageParser$SigningDetails -> ApkSignatureVerifier$SigningDetailsWithDigests
                    Class<?> signingDetailsWithDigests = XposedHelpers.findClassIfExists("android.util.apk.ApkSignatureVerifier.SigningDetailsWithDigests", loadPackageParam.classLoader);
                    if (signingDetailsWithDigests != null) {
                        Constructor<?> withDigestsCtor = XposedHelpers.findConstructorExact(signingDetailsWithDigests, signingDetails, Map.class);
                        withDigestsCtor.setAccessible(true);
                        newInstance = withDigestsCtor.newInstance(newInstance, null);
                    }
                    boolean fired = false;
                    if (throwable != null) {
                        Throwable cause = throwable.getCause();
                        if (throwable.getClass() == packageParserException && error.getInt(throwable) == -103) {
                            methodHookParam.setResult(newInstance);
                            fired = true;
                        }
                        if (cause != null && cause.getClass() == packageParserException && error.getInt(cause) == -103) {
                            methodHookParam.setResult(newInstance);
                            fired = true;
                        }
                    }
                    if (parseErr != null && parseErr == -103) {
                        Object input = methodHookParam.args[0];
                        XposedHelpers.callMethod(input, "reset");
                        methodHookParam.setResult(XposedHelpers.callMethod(input, "success", newInstance));
                        fired = true;
                    }
                    // Log ONE line, only when a signature was actually fabricated (real fire).
                    if (fired) logBypass("verifyV1Signature");
                } catch (Throwable ignored) {
                    // fail-safe: never rethrow into system_server
                }
            }
        });

        // New package has a different signature —— 处理覆盖安装但签名不一致
        hookAllMethods(signingDetails, "checkCapability", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // Carve-out: never grant PERMISSION (4) or AUTH (16) — that would hand out every
                    // privileged permission. https://cs.android.com/.../CertCapabilities
                    // Gate (bypass) evaluated last so the log fires only on the actual bypass.
                    if ((Integer) param.args[1] != 4 && (Integer) param.args[1] != 16 && bypass("checkCapability")) {
                        param.setResult(true);
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
                        // Only bypass the KeySet upgrade check while inside PMS#preparePackage(LI),
                        // i.e. the same-package / different-signature overwrite path. Cheap global
                        // gate FIRST (short-circuits the stack walk when disabled); log only on fire.
                        if (CorePatchConfig.INSTANCE.isEffectivelyEnabled() &&
                                Arrays.stream(Thread.currentThread().getStackTrace())
                                        .anyMatch((o) -> o.getMethodName().startsWith("preparePackage"))
                        ) {
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

        // Deoptimize verifySignatures so the checkCapability / KeySet hooks above actually fire
        // when the verification runs inside it (LSPosed can't hook + deoptimize the same method).
        var utilClass = findClass("com.android.server.pm.PackageManagerServiceUtils", loadPackageParam.classLoader);
        if (utilClass != null) {
            try {
                deoptimizeMethod(utilClass, "verifySignatures");
            } catch (Throwable e) {
                XposedBridge.log("E/" + TAG + " deoptimizing failed " + Log.getStackTraceString(e));
            }
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

    Class<?> getSigningDetails(ClassLoader classLoader) {
        return XposedHelpers.findClass("android.content.pm.PackageParser.SigningDetails", classLoader);
    }
}
