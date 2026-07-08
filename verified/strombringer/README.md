# Strombringer

A headless **LSPosed module** + [Thor](https://github.com/trinadhthatakula/Thor) extension
(`com.valhalla.thor.ext.strombringer`). It has no launcher icon — install it, enable it in LSPosed,
and configure it from **Thor → Extensions → Strombringer**.

## Features

- **Auto-unfreeze on launch** — tap a *suspended* app's real launcher icon and Strombringer asks
  Thor to unsuspend it, then the launch proceeds to the now-active app.
- **CorePatch signature-bypass (danger zone)** — a global toggle that disables Android's signature /
  tamper protection for installs, with a default 20-minute auto-off timer that never survives a
  reboot. Type-to-confirm gated. **Requires Root.** Forked from
  [CorePatch](https://github.com/LSPosed/CorePatch).

## Requirements

- [LSPosed](https://github.com/JingMatrix/LSPosed) (module scope includes the launcher and
  `system_server`); **Root** for CorePatch.
- Thor with the extension launch model (`thor.extension.api.version` **2**, api `3.0.0`).

## Build & trust

Built against `com.trinadhthatakula:thor-extension-api:3.0.0` (`compileOnly`). Verified releases are
signed with the dedicated **"Thor Extensions"** key whose certificate SHA-256 is pinned in Thor's
allowlist — release Thor loads only pinned-signer extensions (debug Thor is lax for local builds).
The config UI runs in **this app's own process** (an exported `ConfigActivity` Thor launches via
`com.valhalla.thor.extension.action.CONFIGURE`), so nothing links against Thor's minified runtime.

```bash
./gradlew :app:assembleRelease   # signed release (needs the Thor Extensions keystore)
```

## License

Strombringer is licensed under the **GNU General Public License, version 2 (GPLv2)**. See
[`LICENSE`](LICENSE) for the full text.

This module includes and/or derives code from [CorePatch](https://github.com/LSPosed/CorePatch)
(tag `4.9`), licensed under GPLv2 (credit: weishu, LSPosed, yujincheng08). CorePatch v4.9 is
GPLv2-**only** (no "or later"), so the combined work is distributed under GPLv2. See [`NOTICE`](NOTICE)
for full attribution.
