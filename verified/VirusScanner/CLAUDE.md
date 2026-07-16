# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone Android APK that is a **dynamically-loaded plugin for [Thor App Manager](https://github.com/trinadhthatakula/Thor)**. It is *not* a normal app — it has no launcher Activity. Thor Core discovers it, reflectively loads its entry-point class, and hosts its UI inside Thor's own process. This particular extension (`AutomationCluster`) lets users group apps into "clusters" and freeze/unfreeze them on demand, via home-screen shortcuts, or on a daily schedule.

## Build & install

```bash
./gradlew assembleDebug                                  # build the extension APK
adb install app/build/outputs/apk/debug/app-debug.apk    # install onto device
```

There are no unit/instrumented tests in this repo. After install, verify via **Thor App Manager → Settings → Manage Extensions**.

Toolchain: JVM 21, AGP `9.4.0-alpha02`, Kotlin `2.4.0`, Compose BOM `2026.06`, Material3 `1.5.0-alpha22` (uses `ExperimentalMaterial3ExpressiveApi`), `minSdk 28` / `compileSdk 37`. Versions live in `gradle/libs.versions.toml`.

## Architecture & the contract you must not break

Two package trees with very different rules:

- **`com.valhalla.thor.extension.api`** — Thor Core's contract (`ThorExtension`, `AutomationExtension`, `ShellExecutor`, `ExtensionDataStore`, `Logger`, `AppIcon`, `DebloatExtension`), consumed from **Maven Central** as `compileOnly("com.trinadhthatakula:thor-extension-api:1.0.1")` (declared in `gradle/libs.versions.toml` as `thor-extension-api` and wired in `app/build.gradle.kts`). It is **`compileOnly`** because the host app provides these classes at runtime (the extension is loaded into Thor's process), so they must NOT be bundled into the APK. Do NOT copy or re-declare these interfaces locally, and never change their package names — Thor Core's class loader links the extension against its own identical interfaces. Core supplies the real `ShellExecutor`/`ExtensionDataStore` instances and sets `Logger.isDebug` at runtime; the extension only *implements*/*consumes* them, never instantiates them.
- **`com.valhalla.thor.ext.*`** — your implementation. Thor Core discovers extensions by scanning for `applicationId`/`namespace` starting with the prefix `com.valhalla.thor.ext.`. To repurpose this template, change `applicationId` + `namespace` in `app/build.gradle.kts`, move the source dir to match, and update the manifest meta-data (below).

### Entry point wiring

`AndroidManifest.xml` `<meta-data android:name="thor.extension.class">` points to the implementing class (`...AutomationCluster`). `thor.extension.api.version` must match the API version Core expects (currently `1`).

`AutomationCluster : AutomationExtension` has two entry points:
- `onTrigger(context, eventType, shellExecutor, dataStore)` — **headless** execution (no UI). `eventType` is `"<action>:<clusterName>"` where action ∈ `freeze | unfreeze | toggle`.
- `ConfigurationScreen(...)` — a `@Composable` UI rendered **inside Thor's host Activity**. Because there's no Activity of our own, navigation is hand-rolled: screen state (`currentScreen`, `selectedClusterName`, `editingClusterName`) lives as `mutableStateOf` fields on the class, and `onBackPressed()` is driven by Thor's back dispatcher.

### How freezing works

App freeze/unfreeze are shell commands run through Core's `ShellExecutor` (Core holds the root/elevated privilege): `pm disable-user --user 0 <pkg>` to freeze, `pm enable <pkg>` to unfreeze, `pm list packages -d <pkg>` to detect frozen state.

### Persistence

Cluster data is a `List<AppCluster>` serialized with `kotlinx.serialization` JSON, stored under the single key `"saved_clusters"` via Core's `ExtensionDataStore`. `AppCluster` (name, packages, isScheduled) is the only persisted model.

### Cross-process triggers (extension ↔ Thor Core)

The extension cannot freeze apps itself from the background — it asks Core to do it:
- **Scheduled alarms:** the UI sets an `AlarmManager` exact alarm targeting `AlarmReceiver`, which on fire broadcasts `com.valhalla.thor.action.TRIGGER_EXTENSION` to package `com.valhalla.thor`, guarded by the `com.valhalla.thor.permission.TRIGGER_EXTENSION` permission, carrying `extension_class` + `trigger_id`. Core then calls `onTrigger`.
- **Home-screen shortcuts:** pinned via `ShortcutManager` with a `thor://extension/trigger?class=...&triggerId=toggle:<name>` deep link that Core resolves.

## Conventions worth matching

- **All `dataStore` reads/writes and all `shellExecutor.execute` calls must be off the main thread** (`Dispatchers.IO`), then hop back to `Dispatchers.Main` for UI/Toast — this prevents ANRs and is the subject of several recent commits. Follow this pattern in any new code.
- Logging goes through the `Logger` object from the `thor-extension-api` artifact (gated by `Logger.isDebug`), never `android.util.Log` directly.
- App icons render via the shared `AppIcon` composable (Coil + the `AppIconModel` key), which Core's image loader resolves — don't load icons by hand.
