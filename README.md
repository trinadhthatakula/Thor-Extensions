# Thor Extensions

Official and community extensions for the [Thor](https://github.com/trinadhthatakula/Thor) Android
app manager, plus the machine-readable **catalog** that powers Thor's in-app *Extensions → Browse*
store.

An extension is a normal Android APK (no launcher icon) whose package id starts with
`com.valhalla.thor.ext.`. Thor discovers installed extensions, reads their metadata, and launches
each extension's own configuration screen. Extensions are built against the
[`thor-extension-api`](https://github.com/trinadhthatakula/Thor-extension-api) contract
(`com.trinadhthatakula:thor-extension-api`, consumed `compileOnly`).

## Repository layout

| Path | What it is |
|------|------------|
| `verified/` | Extensions the maintainer builds, **signs with the dedicated "Thor Extensions" key**, releases, and lists in the catalog. These load on official **release** Thor. |
| `unverified/` | Third-party, **source-only** extensions. Not signed by the maintainer, so they load only on **debug / self-built** Thor. Build them yourself. |
| `catalog/extensions.json` | The catalog Thor's in-app browser fetches (schema v1). |
| `.github/workflows/release.yml` | CI: on a version bump it builds, signs, **pin-verifies**, publishes a GitHub Release, and writes the catalog's `version` / `versionCode` / `apkUrl` / `sha256`. |
| `scripts/build-changed.sh` | CI helper that detects which `verified/*` extension changed and needs a release. |

## Trust model

Thor cannot rely on "same signer as the app" for extensions (the Play and FOSS builds of Thor use
different keys). Instead there is **one dedicated "Thor Extensions" signing key** whose certificate
SHA-256 is **pinned in Thor's allowlist** (`TrustedExtensionSigners.PINS`):

```
762DC455D6F5CE05E7D1848057FDF04362D137B7AB987879AFDF370B10F9498C
```

- **Release Thor loads only extensions signed by a pinned key.** An extension signed by any other
  key (including Thor's own app keys) is refused at load time — fail-closed.
- **CI enforces the same anchor:** the release workflow refuses to publish any APK whose signer
  isn't the pinned key, so nothing reaches the catalog unless it's signed by the trust anchor.
- **Debug / self-built Thor** relaxes this so you can develop and side-load your own extensions.

Two independent layers protect a download from the catalog: **integrity** (Thor verifies the
downloaded APK's SHA-256 against the catalog) and **authenticity** (the pinned-signer check, both
before install and again when Thor loads the installed package).

## Installing an extension

- **Recommended:** open Thor → **Extensions → Browse**, pick an extension, and Install. Thor
  downloads the release APK, verifies its SHA-256 + signer, and installs it.
- **Manual:** download the APK from this repo's [Releases](../../releases) and install it. On
  release Thor it must be signed by the pinned key to load.

Some extensions need extra setup — e.g. **Strombringer** requires the
[LSPosed](https://github.com/JingMatrix/LSPosed) framework; activate its module after installing.

## The catalog (`catalog/extensions.json`)

Schema v1 — one object per extension:

```json
{
  "schemaVersion": 1,
  "extensions": [
    {
      "id": "com.valhalla.thor.ext.example",
      "name": "Example",
      "description": "What it does, in one line.",
      "author": "you",
      "version": "1.00.0",
      "versionCode": 0,
      "verified": true,
      "requiresLSPosed": false,
      "minThorVersionCode": 1900,
      "minSdk": 28,
      "apkUrl": "",
      "sha256": "",
      "sourcePath": "verified/example"
    }
  ]
}
```

You hand-author every field **except** `version`, `versionCode`, `apkUrl`, and `sha256` — CI fills
those on release from the extension's `app/build.gradle.kts` (matched by `sourcePath`). `versionCode`
drives the store's update detection (an installed copy with a lower `versionCode` is offered an
Update), so it must increase on every release. A `verified: false` / empty-`apkUrl` entry is shown as
*source-only, build it yourself*.

## Building & contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: build against `thor-extension-api` `compileOnly`,
ship your config UI as your **own** launched Activity (not inside Thor's process), start your
package id with `com.valhalla.thor.ext.`, and — for a `verified/` listing — add a catalog stub and
open a PR. The starter is [`thor-extension-template`](https://github.com/trinadhthatakula/thor-extension-template).

## License

Each extension carries its own license (see its folder). Strombringer is GPLv2 (it forks CorePatch);
others are GPLv3 to match Thor.
