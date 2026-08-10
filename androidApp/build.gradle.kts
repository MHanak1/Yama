import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Release signing credentials come from a gitignored `keystore.properties` for local release builds,
// and fall back to environment variables (populated from GitHub Secrets) in CI. When neither is
// present — e.g. a fresh clone doing a debug build — signing is simply skipped and the release APK is
// left unsigned rather than failing the build.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun releaseSecret(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

kotlin {
    compilerOptions {
        // JDK 17 to match :shared (sendspin-jvm requires 17); a lower target can't consume its bytecode.
        jvmTarget = JvmTarget.JVM_17
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "net.mhanak.yama"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "net.mhanak.yama"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Driven by the release tag in CI: versionName from YAMA_VERSION, versionCode from the CI run
        // number (a monotonically increasing integer, which Play/Android require for upgrades).
        versionCode = System.getenv("YAMA_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("YAMA_VERSION") ?: "1.0"
    }
    signingConfigs {
        create("release") {
            val storePath = releaseSecret("storeFile", "YAMA_KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = releaseSecret("storePassword", "YAMA_KEYSTORE_PASSWORD")
                keyAlias = releaseSecret("keyAlias", "YAMA_KEY_ALIAS")
                keyPassword = releaseSecret("keyPassword", "YAMA_KEY_PASSWORD")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            //excludes += "META-INF/INDEX.LIST"
            //excludes += "META-INF/io.netty.versions.properties"

        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Attach the release signing config only if credentials resolved; otherwise leave the
            // release APK unsigned (so a keyless clone can still run `assembleRelease` without erroring
            // on a missing storeFile). CI and local release builds provide the keystore.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            // Dependencies that publish only debug/release variants with explicit build-type attributes
            // (e.g. SQLDelight's android-driver) have no `profile` variant — fall back to their release one.
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}