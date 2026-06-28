# Thor App Manager - Extension Template

This repository is a template to build custom extensions/plugins for the [Thor App Manager](https://github.com/trinadhthatakula/Thor). Extensions allow modular recommendations (like custom manufacturer debloat lists), automation rules, backup handlers, and custom installer filters to be loaded dynamically by the core application.

---

## Project Structure

```
thor-extension-template/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/
        └── main/
            ├── AndroidManifest.xml
            └── java/
                └── com/
                    └── valhalla/
                        └── thor/
                            └── ext/
                                └── sample/               # Your extension code
                                    └── SampleDebloatExtension.kt
```

The contract interfaces (`ThorExtension`, `AutomationExtension`, `DebloatExtension`, `ShellExecutor`,
`ExtensionDataStore`, `Logger`, `AppIcon`) are **not** copied into this project — they come from the
published [`io.github.trinadhthatakula:thor-extension-api`](https://github.com/trinadhthatakula/Thor-extension-api)
artifact on Maven Central, declared as `compileOnly` (the host provides them at runtime).

---

## How to Customize & Build Your Extension

### 1. Rename the Package Name
Thor Core discovers extensions by scanning for package names starting with the prefix `com.valhalla.thor.ext.`.
- Modify `applicationId` and `namespace` inside `app/build.gradle.kts` to your custom package name (e.g. `com.valhalla.thor.ext.samsung` or `com.valhalla.thor.ext.mycustomrules`).
- Move and rename your implementation source directory to match your package.

> [!IMPORTANT]
> Do NOT copy or re-declare the `com.valhalla.thor.extension.api` interfaces locally — depend on the published artifact instead. The host links your extension against its own identical interfaces by package name, so the contract must come from the shared artifact, not a local copy.

### 2. Add the API dependency
The contract is published on Maven Central. Add it as **`compileOnly`** so the interfaces are available at compile time but not bundled into your APK (Thor provides them at runtime):

```kotlin
// gradle/libs.versions.toml
// [versions] thorExtensionApi = "1.0.0"
// [libraries] thor-extension-api = { module = "io.github.trinadhthatakula:thor-extension-api", version.ref = "thorExtensionApi" }

// app/build.gradle.kts
dependencies {
    compileOnly(libs.thor.extension.api)
}
```

### 3. Implement the Contract
Implement the `DebloatExtension` interface (or other extension contracts) in your Kotlin class. For example:

```kotlin
package com.valhalla.thor.ext.sample

import com.valhalla.thor.extension.api.DebloatExtension
import com.valhalla.thor.extension.api.ExtensionDebloatItem

class MyExtension : DebloatExtension {
    override val name: String = "My Debloat List"
    override val description: String = "Provides custom recommendations for my device."
    override val version: String = "1.0.0"
    override val author: String = "Contributor Name"
    override val targetManufacturer: String = "xiaomi" // Target manufacturer filter

    override fun getDebloatItems(): List<ExtensionDebloatItem> {
        return listOf(
            ExtensionDebloatItem(
                packageName = "com.miui.analytics",
                recommendation = "recommended",
                description = "MIUI Analytics gatherer. Safe to disable to improve privacy."
            )
        )
    }
}
```

### 4. Declare the Entry Point in the Manifest
The extension has no launcher Activity. In `app/src/main/AndroidManifest.xml`, configure the `<meta-data>` values to point to your implementation class:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:hasCode="true">
        <!-- Point android:value to the fully qualified class name of your implementation -->
        <meta-data
            android:name="thor.extension.class"
            android:value="com.valhalla.thor.ext.sample.SampleDebloatExtension" />
        
        <meta-data
            android:name="thor.extension.api.version"
            android:value="1" />
    </application>
</manifest>
```

### 5. Build and Install
Run the Gradle assemble task to generate your extension APK:

```bash
./gradlew assembleDebug
```

Install the generated APK onto your Android device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open **Thor App Manager -> Settings -> Manage Extensions** to verify that your extension is recognized and loaded!

---

## Security & Signature Verification

- **In Debug builds of Thor**: Bypasses signature checking to make it easy to install and test your extension template locally.
- **In Release builds of Thor**: Requires that all loaded extensions are signed with the **exact same signing certificate/key** as Thor Core. If you build a release build of Thor, you must sign your custom extensions with the identical certificate for them to be accepted.
