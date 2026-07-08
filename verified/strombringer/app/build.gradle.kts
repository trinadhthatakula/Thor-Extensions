import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Dedicated "Thor Extensions" signing key. Loaded from a local (gitignored) keystore.properties
// for local release builds, or EXT_* environment variables in CI (Spec B §2). Never commit keys.
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.valhalla.thor.ext.strombringer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.valhalla.thor.ext.strombringer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1000
        versionName = "1.00.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            } else if (System.getenv("EXT_KEY_ALIAS") != null) {
                // CI/CD build (GitHub Actions): EXT_KEYSTORE_BASE64 is decoded to EXT_KEYSTORE_FILE_PATH.
                keyAlias = System.getenv("EXT_KEY_ALIAS")
                keyPassword = System.getenv("EXT_KEY_PASSWORD")
                storePassword = System.getenv("EXT_KEYSTORE_PASSWORD")
                storeFile = file(System.getenv("EXT_KEYSTORE_FILE_PATH") ?: "thor-extensions-release.jks")
            } else {
                logger.warn("⚠️ keystore.properties not found and EXT_* env vars not set. Release build will not be signed.")
            }
        }
    }

    buildTypes {
        release {
            // Minification is safe now: the config UI runs in the extension's OWN process/activity,
            // so no @Composable lambda or kotlin-stdlib type crosses into Thor. R8 only minifies this
            // self-contained app (standard Compose consumer rules apply). The name-referenced entry
            // points (Xposed entry, Thor metadata class, the config Activity + provider) are kept in
            // proguard-rules.pro / auto-kept from the manifest.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    // Compose + Asgard are BUNDLED. The config UI (ConfigActivity) renders in THIS app's OWN
    // process — Thor launches it by Intent — so it needs its own full Compose/Asgard at runtime.
    // Nothing @Composable crosses into Thor anymore, which is what makes the extension self-
    // contained and immune to Thor's R8 (the old compileOnly + in-host-render model is gone).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.asgard)

    // Provided at runtime, NOT bundled:
    //  • thor-extension-api — only StrombringerExtension (loaded in Thor's process) references
    //    ThorExtension; parent-first classloading resolves it to Thor's copy.
    //  • xposed — LSPosed provides it in system_server.
    compileOnly(libs.thor.extension.api)
    compileOnly(libs.xposed)

    // Plain-JVM unit tests.
    testImplementation("junit:junit:4.13.2")
}
