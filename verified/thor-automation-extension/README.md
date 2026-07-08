# Thor Cluster Automator

A [Thor](https://github.com/trinadhthatakula/Thor) automation extension
(`com.valhalla.thor.ext.automation`). Group apps into **clusters** and freeze / unfreeze a whole
cluster at once — on a daily schedule, from a home-screen shortcut, or by hand. No launcher icon;
configure it from **Thor → Extensions → Thor Cluster Automator**.

## Features

- **App clusters** — create named groups of apps.
- **Scheduled + on-demand freeze/unfreeze** — a daily alarm and pinnable shortcuts trigger a
  cluster; Thor performs the privileged `pm` freeze/unfreeze (`AutomationExtension.onTrigger`).
- Runs its config UI in **its own process** (an exported `ConfigActivity` Thor launches via
  `com.valhalla.thor.extension.action.CONFIGURE`) — no LSPosed/root needed for the extension itself
  (the actual freeze runs through Thor's privilege gateway).

## Requirements

- Thor with the extension launch model (`thor.extension.api.version` **2**, api `3.0.0`). No LSPosed.

## Build & trust

Built against `com.trinadhthatakula:thor-extension-api:3.0.0` (`compileOnly`). Verified releases are
signed with the dedicated **"Thor Extensions"** key whose certificate SHA-256 is pinned in Thor's
allowlist — release Thor loads only pinned-signer extensions (debug Thor is lax for local builds).

```bash
./gradlew :app:assembleRelease   # signed release (needs the Thor Extensions keystore)
```

## License

Licensed under the **GNU General Public License v3.0** to match Thor. See [`LICENSE`](LICENSE).
