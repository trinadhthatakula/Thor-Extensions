package com.valhalla.thor.ext.strombringer

import com.valhalla.thor.ext.strombringer.corepatch.CorePatchHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpp: LoadPackageParam) {
        // Don't hook our own process.
        if (lpp.packageName == "com.valhalla.thor.ext.strombringer") return
        if (lpp.packageName == "android") {            // system_server
            try {
                CorePatchHook(lpp).start()
            } catch (t: Throwable) {
                // FAIL SAFE: never propagate out of system_server class-load.
                XposedBridge.log("Strombringer[corepatch] disabled: $t")
            }
            return
        }
        LaunchAppHook(lpp.classLoader).start()          // launcher packages
    }
}
