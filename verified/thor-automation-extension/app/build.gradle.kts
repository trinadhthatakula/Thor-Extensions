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
    namespace = "com.valhalla.thor.ext.automation"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.valhalla.thor.ext.automation"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1003
        versionName = "1.00.3"
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
            // Minification is safe: the config UI runs in the extension's OWN process/activity, so no
            // @Composable lambda or kotlin-stdlib type crosses into Thor. R8 only minifies this
            // self-contained app. The name-referenced entry points (Thor metadata class, config
            // Activity + provider, alarm receiver) are kept in proguard-rules.pro.
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
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")

    // Thor extension contract. compileOnly: only AutomationCluster (loaded into Thor's process)
    // references it, and parent-first classloading resolves it to Thor's copy — so it must NOT be
    // bundled into the extension APK.
    compileOnly(libs.thor.extension.api)
    // Asgard UI components are now BUNDLED: the config UI (ConfigActivity) renders in THIS app's OWN
    // process, so it needs its own full Asgard at runtime. Nothing @Composable crosses into Thor.
    implementation(libs.asgard)
}
