# Thor Automation extension R8/ProGuard rules.
#
# Thor loads the metadata/automation class by name (thor.extension.class meta-data + onTrigger), and
# launches the config Activity by Intent (ACTION_CONFIGURE). The config UI runs in THIS app's OWN
# process, so nothing crosses into Thor and normal Compose minification applies — only the
# name-referenced entry points must keep their names.

# --- Name-referenced entry points (keep name + members) ---
-keep class com.valhalla.thor.ext.automation.AutomationCluster { *; }
-keep class com.valhalla.thor.ext.automation.AlarmReceiver { *; }
-keep class com.valhalla.thor.ext.automation.ConfigActivity { *; }
-keep class com.valhalla.thor.ext.automation.AutomationConfigProvider { *; }
-keep class com.valhalla.thor.ext.automation.Config { *; }

# @Serializable model decoded in onTrigger (Thor's process) — keep it and its generated serializer so
# kotlinx.serialization can round-trip the cluster list under minification.
-keep class com.valhalla.thor.ext.automation.AppCluster { *; }
-keep class com.valhalla.thor.ext.automation.AppCluster$Companion { *; }
-keepclassmembers class com.valhalla.thor.ext.automation.AppCluster {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Provided at runtime, not packaged (compileOnly) — silence "missing class" warnings ---
-dontwarn com.valhalla.thor.extension.api.**
