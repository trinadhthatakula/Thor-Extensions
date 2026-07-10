package com.valhalla.thor.ext.stormbringer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Runs inside launcher processes (LSPosed scope). When a launch targets a *suspended* app and the
 * user enabled auto-unfreeze, ask Thor (which holds the privilege) to unsuspend it, then let the
 * original launch proceed to the now-active app. Config + restore both travel over provider IPC
 * (no XSharedPreferences).
 */
class LaunchAppHook(private val cl: ClassLoader) {
    fun start() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val intent = p.args.getOrNull(0) as? Intent ?: return
                val target = intent.`package` ?: intent.component?.packageName ?: return
                val ctx = p.thisObject as? Context ?: return
                if (target == ctx.packageName) return
                if (!isSuspended(ctx, target)) return
                if (!autoUnfreezeEnabled(ctx)) return
                requestRestore(ctx, target)
            }
        }
        XposedHelpers.findAndHookMethod(
            Activity::class.java.name, cl, "startActivityForResult",
            Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java, hook
        )
        val ctxWrapper = XposedHelpers.findClass(ContextWrapper::class.java.name, cl)
        XposedHelpers.findAndHookMethod(ctxWrapper, "startActivity", Intent::class.java, hook)
        XposedHelpers.findAndHookMethod(ctxWrapper, "startActivity", Intent::class.java, Bundle::class.java, hook)
    }

    private fun isSuspended(ctx: Context, pkg: String): Boolean = runCatching {
        val m = ctx.packageManager.javaClass.getMethod("isPackageSuspended", String::class.java)
        m.invoke(ctx.packageManager, pkg) as Boolean
    }.getOrDefault(false)

    private fun autoUnfreezeEnabled(ctx: Context): Boolean = runCatching {
        ctx.contentResolver.call(Uri.parse("content://${Config.AUTHORITY}"), "get", null, null)
            ?.getBoolean("value") == true
    }.getOrDefault(false)

    private fun requestRestore(ctx: Context, pkg: String) {
        for (thor in Config.THOR_PACKAGES) {
            val ok = runCatching {
                val extras = Bundle().apply { putString("pkg", pkg) }
                ctx.contentResolver.call(Uri.parse("content://$thor.freezerbridge"), "restore", null, extras)
                    ?.getBoolean("ok") == true
            }.getOrDefault(false)
            if (ok) {
                repeat(6) { if (!isSuspended(ctx, pkg)) return; Thread.sleep(60) }
                return
            }
        }
    }
}
