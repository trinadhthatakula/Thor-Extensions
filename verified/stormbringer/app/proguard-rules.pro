# Stormbringer R8/ProGuard rules.
#
# Dual-role APK: LSPosed loads the Xposed entry by name (assets/xposed_init), Thor loads the
# metadata class by name (thor.extension.class meta-data), and Thor launches the config Activity by
# Intent (ACTION_CONFIGURE). The config UI runs in THIS app's OWN process, so nothing crosses into
# Thor and normal Compose minification applies — only the name-referenced entry points must keep
# their names.

# --- Name-referenced entry points (keep name + members) ---
-keep class com.valhalla.thor.ext.stormbringer.XposedEntry { *; }
-keep class com.valhalla.thor.ext.stormbringer.StormbringerExtension { *; }
-keep class com.valhalla.thor.ext.stormbringer.StormbringerConfigProvider { *; }
-keep class com.valhalla.thor.ext.stormbringer.ConfigActivity { *; }
-keep class com.valhalla.thor.ext.stormbringer.Config { *; }
# Xposed hook classes are invoked by the framework via the bridge — keep them from being removed.
-keep class com.valhalla.thor.ext.stormbringer.corepatch.** { *; }

# --- Provided at runtime, not packaged (compileOnly) — silence "missing class" warnings ---
# Xposed API is provided by LSPosed in system_server; thor-extension-api by Thor's process.
-dontwarn de.robv.android.xposed.**
-dontwarn com.valhalla.thor.extension.api.**
