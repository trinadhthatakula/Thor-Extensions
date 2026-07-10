# Stormbringer — Installer-Spoof Slice — Implementation Plan (Plan 2 of Spec A)

> **⚠️ SUPERSEDED / REMOVED (2026-07-07).** Built and device-tested, then removed. The in-process
> installer-spoof (`getInstallerPackageName`/`getInstallSourceInfo` → Play) only defeats *soft*
> self-checks. The real-world target (a Play-gated app, Apphive) uses the **Play Integrity API**:
> its verdict is computed & signed by GMS/Google servers from the **real** installer attribution,
> not the app's in-process getter — so the hook could not move it (device logs confirmed the hook
> fired correctly yet the gate held). Base Thor's root **"reinstall with Play"** (real `-i
> com.android.vending` attribution) *does* satisfy it and already ships. Kept here as the historical
> record; the feature was reverted in Stormbringer commit *(remove installer-spoof feature)*.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Let the user make chosen apps report `getInstallerPackageName()`/`getInstallSourceInfo()` as `com.android.vending` (Play Store), so apps that gate on install source behave as if Play-installed — **without reinstalling**.

**Architecture:** A per-app Xposed hook (runs in each *target* app's own process, LSPosed-scoped to it) intercepts the `ApplicationPackageManager` installer-source getters and returns Play for packages in a user-managed spoof set. The set is stored in Stormbringer's existing config provider; Thor's config screen gains an app-picker to manage it. **100% inside the Stormbringer repo** — no base-Thor changes.

**Tech Stack:** Kotlin, Xposed API (`compileOnly`), Jetpack Compose (host-provided), `thor-extension-api` (`compileOnly`). All in `/Users/trinadhthatakula/StudioProjects/Stormbringer`.

## Global Constraints
- Repo: `/Users/trinadhthatakula/StudioProjects/Stormbringer` (branch `main`). No base-Thor changes.
- Config travels over the **existing provider IPC** (`content://com.valhalla.thor.ext.stormbringer.config`) — never `XSharedPreferences`/`MODE_WORLD_READABLE` (minSdk 28 forbids it).
- The spoof hook runs in the **target app's** process; it must apply only to packages in the spoof set and degrade to no-op on any reflection failure (never crash the host app).
- Spoofed installer value = `com.android.vending`.
- Xposed API stays `compileOnly` (APK bundles no `de.robv`).
- Two-step reality (document in the UI): a spoofed app must ALSO be added to Stormbringer's **LSPosed scope** (so the module loads into it). Thor's UI manages the *set*; LSPosed Manager manages the *scope*.

---

## File Structure
| File | Responsibility | Change |
|---|---|---|
| `.../stormbringer/Config.kt` | add spoof-set keys + provider `getSet`/`setSet` | Modify |
| `.../stormbringer/InstallerSpoofHook.kt` | hook installer getters → Play, for set members | Create |
| `.../stormbringer/XposedEntry.kt` | also run the spoof hook when a scoped app loads | Modify |
| `.../stormbringer/StormbringerExtension.kt` | app-picker section in `ConfigurationScreen` | Modify |

---

## Task 1: Config — spoof package set

**Files:** Modify `app/src/main/java/com/valhalla/thor/ext/stormbringer/Config.kt`.

**Interfaces:**
- Produces: `Config.KEY_SPOOF = "spoof_installer"`; provider methods `"getSpoofSet"` → `Bundle{ "value": String[] }`, `"setSpoofSet"` (extras `"value": String[]`); backed by `StringSet` in the same private prefs.

- [ ] **Step 1: Add the key** to `object Config`:
```kotlin
    const val KEY_SPOOF = "spoof_installer"
    const val PLAY = "com.android.vending"
```
- [ ] **Step 2: Extend `StormbringerConfigProvider.call`** — add two branches inside the existing `when (method)` (keep `get`/`set` as-is):
```kotlin
            "getSpoofSet" -> out.putStringArray(
                "value", prefs.getStringSet(Config.KEY_SPOOF, emptySet()).orEmpty().toTypedArray()
            )
            "setSpoofSet" -> prefs.edit()
                .putStringSet(Config.KEY_SPOOF, (extras?.getStringArray("value") ?: emptyArray()).toSet())
                .apply()
```
- [ ] **Step 3: Build** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `git commit -am "feat(stormbringer): config stores installer-spoof package set"`

## Task 2: The installer-spoof hook

**Files:** Create `app/src/main/java/com/valhalla/thor/ext/stormbringer/InstallerSpoofHook.kt`.

**Interfaces:**
- Consumes: `Config.AUTHORITY`, `Config.PLAY`, `"getSpoofSet"` (Task 1).
- Produces: `InstallerSpoofHook(classLoader).startIfSpoofed(ctx, ownPackage)` — hooks only if `ownPackage` is in the set.

- [ ] **Step 1: Implement** — hook the concrete `android.app.ApplicationPackageManager` installer getters; return Play. Read the set via provider IPC (a hooked app has a `Context`).
```kotlin
package com.valhalla.thor.ext.stormbringer

import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** Runs in a TARGET app's process; if that app is in the user's spoof set, makes the installer
 *  getters report Play. All reflection is guarded so a miss never crashes the host app. */
class InstallerSpoofHook(private val cl: ClassLoader) {

    fun startIfSpoofed(ctx: Context) {
        if (ctx.packageName !in spoofSet(ctx)) return
        val pmClass = runCatching {
            XposedHelpers.findClass("android.app.ApplicationPackageManager", cl)
        }.getOrNull() ?: return

        val returnPlay = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { p.result = Config.PLAY }
        }
        // Deprecated but still widely used (API 30-): String getInstallerPackageName(String)
        runCatching {
            XposedHelpers.findAndHookMethod(pmClass, "getInstallerPackageName", String::class.java, returnPlay)
        }

        // API 30+: InstallSourceInfo getInstallSourceInfo(String) — rewrite its installing package.
        runCatching {
            XposedHelpers.findAndHookMethod(pmClass, "getInstallSourceInfo", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val info = p.result ?: return
                        // InstallSourceInfo is final; overwrite its private installing/initiating fields.
                        runCatching { XposedHelpers.setObjectField(info, "mInstallingPackageName", Config.PLAY) }
                        runCatching { XposedHelpers.setObjectField(info, "mInitiatingPackageName", Config.PLAY) }
                    }
                })
        }
    }

    private fun spoofSet(ctx: Context): Set<String> = runCatching {
        ctx.contentResolver.call(Uri.parse("content://${Config.AUTHORITY}"), "getSpoofSet", null, null)
            ?.getStringArray("value")?.toSet().orEmpty()
    }.getOrDefault(emptySet())
}
```
- [ ] **Step 2: Build** → SUCCESSFUL; confirm `unzip -l …/app-debug.apk | grep -c de/robv` = 0.
- [ ] **Step 3: Commit** `git commit -am "feat(stormbringer): installer-spoof hook (getInstallerPackageName/getInstallSourceInfo -> Play)"`

## Task 3: Wire the hook into `XposedEntry`

**Files:** Modify `.../stormbringer/XposedEntry.kt`.

- [ ] **Step 1:** run both hooks per loaded package (the launcher hook is a no-op in non-launcher apps; the spoof hook is a no-op unless the app is in the set). A hooked app's `Context` is obtained lazily — hook `Application.attach`/`onCreate` is overkill; instead the spoof hook reads config when it first needs a Context. Simplest: pass the loaded package and let each hook self-gate. Replace `handleLoadPackage`:
```kotlin
    override fun handleLoadPackage(lpp: LoadPackageParam) {
        if (lpp.packageName == "com.valhalla.thor.ext.stormbringer") return
        LaunchAppHook(lpp.classLoader).start()
        // The spoof hook needs a Context; hook Application.onCreate to grab it, then self-gate on the set.
        XposedHelpers.findAndHookMethod(
            android.app.Application::class.java, "onCreate",
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val app = p.thisObject as? android.content.Context ?: return
                    InstallerSpoofHook(lpp.classLoader).startIfSpoofed(app)
                }
            }
        )
    }
```
(Add `import de.robv.android.xposed.XposedHelpers`.)
- [ ] **Step 2: Build** → SUCCESSFUL. **Commit** `git commit -am "feat(stormbringer): run installer-spoof hook per scoped app"`

## Task 4: Thor UI — app picker

**Files:** Modify `.../stormbringer/StormbringerExtension.kt` — add a "Spoof installer (Play Store)" section under the auto-unfreeze toggle: the current spoof set (read via `getSpoofSet`) rendered as a searchable list of user apps (from `LocalContext.current.packageManager`), each an `AsgardSettingToggleRow` that adds/removes the package and persists via `setSpoofSet`. Include a one-line note: "Also enable each app in Stormbringer's LSPosed scope." (Compose+Asgard, mirroring the existing screen; the load-bearing parts are the `getSpoofSet`/`setSpoofSet` IPC calls and updating the in-memory set state.)

- [ ] **Step 1:** implement the picker section (reuse `AsgardSettingToggleRow`; list `packageManager.getInstalledApplications(0).filter { non-system }`).
- [ ] **Step 2: Build** → SUCCESSFUL. **Commit** `git commit -am "feat(stormbringer): Thor config app-picker for installer spoof"`

## Task 5: Device verification
- [ ] Reinstall Stormbringer; in **LSPosed** add a test app (that checks its installer, e.g. a Play-gated app) to Stormbringer's scope; reboot/restart that app.
- [ ] In Thor → Stormbringer, enable **Spoof installer** for that app.
- [ ] Verify: `adb shell dumpsys package <pkg> | grep -i installerPackageName` is unchanged (real installer), but from *inside* the app `getInstallerPackageName()` now returns `com.android.vending` (the app's install-source check passes). Confirm apps NOT in the set are unaffected, and non-scoped apps unaffected.

## Self-Review
- **Spec A coverage:** §5.4 installer-spoof hook → Tasks 2/3; config → Task 1; Thor control surface → Task 4; device proof → Task 5. No base-Thor change (correct — spoof needs none).
- **Risks:** `getInstallSourceInfo` field names (`mInstallingPackageName`/`mInitiatingPackageName`) are the Android 14/16 internals — Task 5 validates on-device; if a field name differs, adjust from the failing device (the `getInstallerPackageName` hook alone already covers most install-source checks). Compose bundled `implementation` (size only).
- **Placeholder note:** Task 4's picker UI is described, not fully coded — the load-bearing IPC (`getSpoofSet`/`setSpoofSet`) is specified; the surrounding list is boilerplate mirrored from the existing `ConfigurationScreen`.
