import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("YamaDatabase") {
            packageName.set("net.mhanak.yama.db")
        }
    }
}

// ── Generated build metadata ────────────────────────────────────────────────
// The version string shown in the About screen is generated at build time so it always matches the
// release. CI exports YAMA_VERSION (derived from the git tag — see .github/workflows/release.yml);
// local/dev builds with no env fall back to "dev". The generated file lands on commonMain below.
val yamaVersion: String = System.getenv("YAMA_VERSION")?.takeIf { it.isNotBlank() } ?: "dev"

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo/commonMain/kotlin")
    val version = yamaVersion
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("net/mhanak/yama")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildInfo.kt").writeText(
            """
            |package net.mhanak.yama
            |
            |/** Generated at build time — do not edit. See shared/build.gradle.kts (generateBuildInfo). */
            |internal object BuildInfo {
            |    const val VERSION: String = "$version"
            |}
            |
            """.trimMargin()
        )
    }
}

kotlin {

    // Explicitly (re)apply the default source-set hierarchy. Our custom `jvmCommonMain` source set adds
    // manual `dependsOn` edges below, which otherwise suppress the auto-applied default template and
    // would drop the standard commonTest→per-target-test edges; calling it here keeps those defaults and
    // lets the jvmCommonMain edges layer on top.
    applyDefaultHierarchyTemplate()

    // JDK 17 across both JVM targets: sendspin-jvm (Music Assistant Sendspin player) targets JDK 17.
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    androidLibrary {
       namespace = "net.mhanak.yama.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_17
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
        // Intermediate source set shared by the two JVM targets (android + desktop jvm). KMP's default
        // hierarchy has no jvm+android group, so we create one by hand: all Sendspin/Music-Assistant
        // playback code is identical JVM bytecode for both platforms (only PcmAudioSink diverges), so it
        // lives here once instead of being duplicated in androidMain and jvmMain.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Sendspin protocol client for Music Assistant playback (JDK 17, no iOS).
                implementation(libs.sendspin.jvm)
                // SendSpinClient's constructor names OkHttpClient + Moshi, but sendspin-jvm's POM scopes
                // them `runtime` (off our compile classpath), so declare them explicitly here.
                implementation(libs.okhttp)
                implementation(libs.moshi.kotlin)
            }
        }
        androidMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation("org.slf4j:slf4j-simple:2.0.13")
                implementation(libs.androidx.security.crypto)
                implementation(libs.androidx.activity.compose)
                implementation("androidx.media3:media3-exoplayer:1.5.1")
                implementation("androidx.media3:media3-session:1.5.1")
                implementation("com.vanniktech:blurhash:0.3.0")
                implementation(libs.sqldelight.androidDriver)
                implementation(libs.androidx.work.runtime)
                // Ktor OkHttp engine for SubsonicSource (JVM/Android only — engine is platform-specific)
                implementation(libs.ktor.client.okhttp)
            }
        }
        // Register the generated BuildInfo.kt (version string) as a commonMain source. Passing the task
        // provider to srcDir makes Gradle wire the compile→generate task dependency automatically.
        commonMain {
            kotlin.srcDir(generateBuildInfo)
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

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            // Ktor multiplatform artifacts for SubsonicSource
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // WebSocket client plugin for MusicAssistantSource's command/event socket.
            implementation(libs.ktor.client.websockets)
        }
        jvmMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("ch.qos.logback:logback-classic:1.5.6")
                implementation(libs.jna)
                implementation(libs.jna.platform)
                // libvlc wrapper for desktop audio playback. Requires libvlc present at runtime
                // (a package dependency on Linux, bundled on Windows).
                implementation("uk.co.caprica:vlcj:4.8.3")
                implementation("com.vanniktech:blurhash:0.3.0")
                // Embedded-tag reading for the local-files source (ID3 / Vorbis / FLAC / MP4).
                implementation("net.jthink:jaudiotagger:3.0.1")
                // MPRIS D-Bus integration for Linux media key / taskbar / system tray support.
                // The transport jar is discovered at runtime via ServiceLoader; without it the
                // MprisService silently no-ops (the start() guard catches the missing-transport error).
                implementation("com.github.hypfvieh:dbus-java-core:5.1.0")
                implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.0")
                implementation(libs.sqldelight.sqliteDriver)
                // Ktor OkHttp engine for SubsonicSource (JVM/Android only — engine is platform-specific)
                implementation(libs.ktor.client.okhttp)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}