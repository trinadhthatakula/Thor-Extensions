# Strombringer R8/ProGuard rules.
#
# This is a dual-role APK: LSPosed loads the Xposed entry by name (assets/xposed_init),
# and Thor loads the extension config class by name (thor.extension.class meta-data).
# Those name-referenced entry points MUST keep their names; everything else can be shrunk.

# --- Name-referenced entry points (keep name + members) ---
-keep class com.valhalla.thor.ext.strombringer.XposedEntry { *; }
-keep class com.valhalla.thor.ext.strombringer.StrombringerExtension { *; }
-keep class com.valhalla.thor.ext.strombringer.StrombringerConfigProvider { *; }
-keep class com.valhalla.thor.ext.strombringer.Config { *; }

# --- Compile-only, host/framework-provided at runtime (never bundled) ---
# Xposed API is provided by LSPosed; Asgard + extension-api by Thor's process.
-dontwarn de.robv.android.xposed.**
-dontwarn com.trinadhthatakula.asgard.**
-dontwarn com.valhalla.thor.**

# Keep the Xposed hook-callback classes intact (they are invoked by the framework via
# the Xposed bridge; renaming their names is fine but keep them from being removed).
-keep class com.valhalla.thor.ext.strombringer.corepatch.** { *; }
