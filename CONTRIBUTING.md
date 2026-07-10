# Contributing an extension

Thanks for building for [Thor](https://github.com/trinadhthatakula/Thor)! Start from the
[`thor-extension-template`](https://github.com/trinadhthatakula/thor-extension-template) — it's a
working example of everything below.

## 1. Build against the API

Depend on the contract **`compileOnly`** — Thor provides it at runtime, so you must not bundle it:

```kotlin
dependencies {
    compileOnly("com.trinadhthatakula:thor-extension-api:3.0.0")
}
```

Implement one of the contracts:

- **`ThorExtension`** — metadata only (`name` / `description` / `version` / `author`). Thor loads
  this class in **its own** process to list your extension, so keep it trivial: no Compose, no
  Asgard, no privileged calls.
- **`AutomationExtension : ThorExtension`** — **deprecated/retired.** Its `suspend fun onTrigger(...)`
  ran extension code inside Thor's process; Thor no longer does that. Perform privileged package
  actions via Thor's `ExtensionOpsProvider` instead — see "Requesting privileged operations from
  Thor" below.
- **`DebloatExtension : ThorExtension`** — a manufacturer debloat list (pure data).

Declare your class in the manifest and start your package id with `com.valhalla.thor.ext.`:

```xml
<meta-data android:name="thor.extension.class"
    android:value="com.valhalla.thor.ext.example.ExampleExtension" />
<meta-data android:name="thor.extension.api.version" android:value="2" />
```

## 2. Configuration UI — render it in YOUR OWN process

**Do not** try to render config UI inside Thor. Ship an exported `Activity` with an intent-filter
for `ThorExtensionContract.ACTION_CONFIGURE` (`com.valhalla.thor.extension.action.CONFIGURE`); Thor
launches it when the user taps *Configure*:

```xml
<activity android:name=".ConfigActivity" android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="com.valhalla.thor.extension.action.CONFIGURE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Because it runs in your process, **bundle your own Compose/UI** (`implementation`, not
`compileOnly`) and set `android.enableR8.fullMode=false` (R8 full-mode mis-optimizes Compose default
args). Thor passes its theme as optional extras (`ThorExtensionContract.EXTRA_THEME_MODE` /
`EXTRA_DYNAMIC_COLOR` / `EXTRA_AMOLED`) so you can match its look. Persist settings in your own
`ContentProvider`/prefs, not Thor's.

> The old `@Composable ConfigurationScreen` / `AppIcon()` helper were **removed in api 3.0.0**. If
> you're upgrading from 2.x, move your config UI into your own Activity (see the template diff).

## 3. Submit

**Source-only (`unverified/`).** Open a PR adding your extension's source under `unverified/<name>/`.
Because it isn't signed by the project's trust anchor, it loads only on **debug / self-built** Thor —
users build it themselves. This is the path for third-party authors.

**Verified (`verified/`).** Verified extensions are built and signed by the maintainer with the
dedicated **"Thor Extensions"** key and listed in the catalog; you can't self-sign into `verified/`.
To propose one, open a PR with the source under `verified/<name>/` **and a catalog stub** — CI
**hard-fails** a release for a `verified/` extension that has no catalog entry:

```json
{
  "id": "com.valhalla.thor.ext.<name>",
  "name": "…", "description": "…", "author": "…",
  "version": "1.00.0",
  "versionCode": 0,
  "verified": true,
  "requiresLSPosed": false,
  "minThorVersionCode": 1900,
  "minSdk": 28,
  "apkUrl": "", "sha256": "",
  "sourcePath": "verified/<name>"
}
```

Fill every field **except** `version` / `versionCode` / `apkUrl` / `sha256` (CI writes those on
release from your `app/build.gradle.kts`, matching on `sourcePath`). `versionCode` drives the store's
**update detection**, so it must increase on every release you want users to be offered.

## 4. Releasing (verified extensions)

CI releases automatically when a `verified/*` extension's `versionName` (in `app/build.gradle.kts`)
is bumped and merged to `main`. **Bump `versionCode` in the same commit** — the release is *triggered*
by the `versionName` tag, but the store's update detection compares `versionCode`, so a
`versionCode`-only change would be skipped (its tag already exists) while a `versionName`-only change
would release without any device being offered the update:

1. `scripts/build-changed.sh` detects the changed extension, derives the tag `<dir>-v<version>`
   (e.g. `stormbringer-v1.00.0`) and asset `<dir>-<version>.apk`, and reads `versionCode` from
   `app/build.gradle.kts`.
2. The workflow builds `:app:assembleRelease`, signs with the dedicated key, and **rejects any APK
   not signed by the pinned certificate** (`762DC455…F9498C`).
3. It publishes a GitHub Release and writes the catalog entry's `version`, `versionCode`, `apkUrl`,
   and `sha256`.

Bumping the version but forgetting the catalog stub → the run hard-fails (by design, so nothing ends
up released-but-uncatalogued). Re-releasing an already-tagged version is skipped (idempotent).

## Requesting privileged operations from Thor

Extensions don't run code inside Thor. To perform a privileged package action, call Thor's
`ExtensionOpsProvider` (Thor cold-starts if needed):

- **URI:** `content://<thorPackage>.extensionops` — try `com.valhalla.thor`, then `com.valhalla.thor.debug`.
- **method:** `"freeze"` | `"unfreeze"` | `"toggle"`
- **extras:** `Bundle { putStringArray("packages", …) }`
- **returns:** `Bundle { "ok": Boolean, "count": Int }`

Thor verifies the caller is a pinned-signer extension (debug builds relax this), freezes via
disable/enable (Thor's Suspend mode is not used — its suspend path is unavailable on release), and never
operates on Thor's own or your package. Call it OFF the main thread (`ContentResolver.call` is a
synchronous IPC). Your app needs no special permission —
just package visibility (declare `<queries><package android:name="com.valhalla.thor"/></queries>` or hold
`QUERY_ALL_PACKAGES`). See `verified/thor-automation-extension` (`ThorOps.kt`) for a reference client.

## Checklist

- [ ] `compileOnly("com.trinadhthatakula:thor-extension-api:3.0.0")`
- [ ] package id starts with `com.valhalla.thor.ext.`
- [ ] `thor.extension.class` + `thor.extension.api.version="2"` meta-data
- [ ] config UI in your own exported `ConfigActivity` (CONFIGURE intent-filter), not in Thor
- [ ] `android.enableR8.fullMode=false`
- [ ] a LICENSE in your folder
- [ ] (verified only) a catalog stub in `catalog/extensions.json`
