buildscript {
    dependencies {
        classpath(libs.android.shortcut.gradle)
    }
}

plugins {
    alias(kotlinx.plugins.serialization) apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.sqldelight) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-json-okio-jvm:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.6.3",
                "org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm:1.6.3",
            )
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
