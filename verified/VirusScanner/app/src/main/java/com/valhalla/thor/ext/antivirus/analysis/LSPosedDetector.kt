package com.valhalla.thor.ext.antivirus.analysis

class LSPosedDetector {

    /**
     * Checks if LSposed/Xposed hooks are active inside the current runtime process.
     */
    fun hasActiveHooks(): Boolean {
        // 1. Check stack trace frames for xposed or lsposed classes
        try {
            throw Exception("LSPosed audit")
        } catch (e: Exception) {
            for (element in e.stackTrace) {
                if (element.className.contains("de.robv.android.xposed") || 
                    element.className.contains("org.lsposed.lsposed")) {
                    return true
                }
            }
        }

        // 2. Check system classloader for XposedBridge presence
        try {
            ClassLoader.getSystemClassLoader().loadClass("de.robv.android.xposed.XposedBridge")
            return true
        } catch (e: Exception) {
            // Class not found, normal environment
        }

        return false
    }
}
