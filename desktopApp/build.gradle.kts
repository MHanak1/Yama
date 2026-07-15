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
            packageName = "net.mhanak.yamao"
            packageVersion = "1.0.0"

            linux { iconFile.set(project.file("src/main/resources/icon.png")) }
            windows { iconFile.set(project.file("src/main/resources/icon.ico")) }
        }
    }
}