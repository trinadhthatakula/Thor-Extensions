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
                            ├── extension/
                            │   └── api/                  # Duplicate of Core contracts
                            │       ├── ThorExtension.kt
                            │       └── DebloatExtension.kt
                            └── ext/
                                └── sample/               # Your extension code
                                    └── SampleDebloatExtension.kt
```

---

## How to Customize & Build Your Extension

### 1. Rename the Package Name
Thor Core discovers extensions by scanning for package names starting with the prefix `com.valhalla.thor.ext.`.
- Modify `applicationId` and `namespace` inside `app/build.gradle.kts` to your custom package name (e.g. `com.valhalla.thor.ext.samsung` or `com.valhalla.thor.ext.mycustomrules`).
- Move and rename your implementation source directory to match your package.

> [!IMPORTANT]
> Do NOT rename or move classes inside the `com.valhalla.thor.extension.api` package. These interfaces must retain their exact package names so that the Thor Core class loader can link them.

### 2. Implement the Contract
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

### 3. Declare the Entry Point in the Manifest
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

### 4. Build and Install
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
