# Task 3 Report — Wire installer-spoof hook into XposedEntry

**Status:** COMPLETE

**Commit:** `bfdb506` — `feat(stormbringer): run installer-spoof hook per scoped app`

**Build + de/robv:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; `unzip -l app-debug.apk | grep -c de/robv` → 0 (Xposed API stays compileOnly, nothing bundled).

## What changed
`app/src/main/java/com/valhalla/thor/ext/stormbringer/XposedEntry.kt`:
- Added `import de.robv.android.xposed.XposedHelpers`.
- `handleLoadPackage` now, after `LaunchAppHook(...).start()`, hooks `Application.onCreate` to grab the target app's `Context` and calls `InstallerSpoofHook(lpp.classLoader).startIfSpoofed(app)`, which self-gates on the spoof set.

## Concerns
- None blocking. Runtime behavior (spoof actually applying) is validated on-device in Task 5; this task only wires and builds.
