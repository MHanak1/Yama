import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {

    jvm()
    
    androidLibrary {
       namespace = "net.mhanak.yama.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }

    val jellyfinSdkVersion = "1.8.10"

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation("org.slf4j:slf4j-simple:2.0.13")
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.activity.compose)
            implementation("androidx.media3:media3-exoplayer:1.5.1")
            implementation("androidx.media3:media3-session:1.5.1")
            implementation("com.vanniktech:blurhash:0.3.0")
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation("org.jellyfin.sdk:jellyfin-core:${jellyfinSdkVersion}")
            implementation("io.coil-kt.coil3:coil-compose:3.4.0")
            implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")
            implementation("com.russhwolf:multiplatform-settings:1.2.0")
            implementation("com.russhwolf:multiplatform-settings-no-arg:1.2.0")
            implementation(libs.kotlinx.serialization.json)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation("dev.chrisbanes.haze:haze-materials:1.7.2")
            implementation(libs.navigation.compose)

            implementation(libs.kmpalette.core)
            implementation(libs.materialKolor)
            implementation(libs.reorderable)
        }
        jvmMain.dependencies {
            implementation("ch.qos.logback:logback-classic:1.5.6")
            implementation(libs.jna)
            implementation(libs.jna.platform)
            // libvlc wrapper for desktop audio playback. Requires libvlc present at runtime
            // (a package dependency on Linux, bundled on Windows).
            implementation("uk.co.caprica:vlcj:4.8.3")
            implementation("com.vanniktech:blurhash:0.3.0")
            // Embedded-tag reading for the local-files source (ID3 / Vorbis / FLAC / MP4).
            implementation("net.jthink:jaudiotagger:3.0.1")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}