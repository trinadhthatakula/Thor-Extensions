# Task 4 Report — Thor UI installer-spoof app picker

**Status:** Complete

**Commit:** `b79ac31` — feat(stormbringer): Thor config app-picker for installer spoof

**Build:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL in 2s.

## What changed
Replaced the entire contents of
`app/src/main/java/com/valhalla/thor/ext/stormbringer/StormbringerExtension.kt`
with the specified version: keeps the auto-unfreeze toggle and adds a
"Spoof installer as Play Store" section. The screen now uses a `LazyColumn`
listing non-system user apps (from `LocalContext.current.packageManager`),
each an `AsgardSettingToggleRow` that adds/removes the package from the spoof
set and persists it via the `getSpoofSet`/`setSpoofSet` provider IPC calls.
All `contentResolver.call` sites were preserved verbatim. `AsgardHeader`,
`AsgardSettingToggleRow`, and `items` all resolved with no signature changes
needed (they matched the existing screen's Asgard 2.0 usage).

## Concerns
- The picker has no search field despite the plan's "searchable list" wording;
  the specified file uses a plain scrollable `LazyColumn`, which was implemented
  exactly as dictated.
- `spoofSet`/`autoUnfreeze` are read once via `remember` (no re-read on
  provider-side change); acceptable since this screen is the sole writer.
- Depends on Tasks 1–3 (provider `getSpoofSet`/`setSpoofSet`, hook, wiring)
  being in place for end-to-end behavior; only compile-time integration was
  verified here. Device verification is Task 5 (not in scope).
