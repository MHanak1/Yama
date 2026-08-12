import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // JDK 17 to match :shared (sendspin-jvm requires 17); the default 1.8 target can't consume it.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Align the Java compile target with Kotlin's (17); otherwise compileJava defaults to the Gradle JDK
// (21) and Gradle rejects the Java/Kotlin JVM-target mismatch.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "net.mhanak.yama.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Yama"
            // Driven by the release tag in CI (YAMA_VERSION, e.g. "1.2.0"); "1.0.0" for local builds.
            // Must be numeric x.y.z — jpackage/MSI reject suffixes like "-beta" (see RELEASING.md).
            packageVersion = System.getenv("YAMA_VERSION") ?: "1.0.0"
            description = "Yama - Yet Another Music App"
            // Baked into the Windows MSI `Manufacturer` field, which WiX encodes in code page 1252.
            // Keep this ASCII: "ł" (U+0142) lives in CP1250, not 1252, and makes light.exe fail with
            // LGHT0311. (The "©" and em-dash above are fine — both exist in 1252.)
            vendor = "Michal Hanak"
            copyright = "© 2026 Michal Hanak"

            // Files placed here are copied into the packaged app image and exposed at runtime via the
            // `compose.application.resources.dir` system property. Per-platform subfolders (e.g.
            // `windows-x64/`) are merged with `common/`. The release workflow drops the bundled libvlc
            // into `resources/windows-x64/vlc/` on the Windows runner; nothing VLC-related is committed.
            appResourcesRootDir.set(project.file("resources"))

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                // libvlc is a runtime system dependency on Linux (bundled only on Windows). Users of the
                // .deb / AUR package need the `vlc` package installed — documented in RELEASING.md.
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Yama"
                // Stable across releases so each new MSI *upgrades* the previous install instead of
                // installing side-by-side. Never change this once a release ships.
                upgradeUuid = "4b4dab44-881a-496c-b745-6e5759b9bf1f"
            }
        }
    }
}
