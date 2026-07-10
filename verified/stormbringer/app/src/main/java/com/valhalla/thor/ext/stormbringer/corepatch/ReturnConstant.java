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
 * Rewired: the {@code XSharedPreferences} gate was replaced by the GLOBAL CorePatch master flag
 * (see CorePatchConfig / XposedHelper#bypass).
 */
package com.valhalla.thor.ext.stormbringer.corepatch;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Forces a hooked method to return a constant, but only while CorePatch is effectively enabled (the
 * GLOBAL master flag + auto-off window, see {@link XposedHelper#bypass}). Otherwise the original
 * method runs. On an actual fire it emits ONE Stormbringer log naming {@link #hookName}. Fail-safe:
 * any throw is swallowed so nothing is ever rethrown into system_server.
 */
public class ReturnConstant extends XC_MethodHook {
    private final String hookName;
    private final Object value;

    public ReturnConstant(String hookName, Object value) {
        this.hookName = hookName;
        this.value = value;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        try {
            if (XposedHelper.bypass(hookName)) {
                param.setResult(value);
            }
        } catch (Throwable ignored) {
            // never rethrow into system_server
        }
    }
}
