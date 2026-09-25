@file:Suppress("ChromeOsAbiSupport")

import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    id("com.github.zellius.shortcut-helper")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
}

shortcutHelper.setFilePath("./shortcuts.xml")

val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.kanade.tachiyomi.vibe"

        versionCode = 20
        versionName = "0.17.1"

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
        buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        buildConfigField("boolean", "PREVIEW", "false")
        // Paddle batching is production-pinned to B1. Staged/debug builds may
        // request a larger size, but the device policy must approve the exact
        // provider/width/batch cell or the explicit CPU B1 emergency path wins.
        buildConfigField("boolean", "PADDLE_BATCHING_STAGED", "false")
        buildConfigField("int", "PADDLE_BATCHING_REQUESTED_BATCH", "1")

        ndk {
            abiFilters += supportedAbis
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        named("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        named("debug") {
            versionNameSuffix = "-${getCommitCount()}"
            applicationIdSuffix = ".debug"
            isPseudoLocalesEnabled = true
            buildConfigField("boolean", "PADDLE_BATCHING_STAGED", "true")
            buildConfigField("int", "PADDLE_BATCHING_REQUESTED_BATCH", "4")
        }
        named("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            buildConfigField("boolean", "PREVIEW", "true")

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("release")
            val debugType = getByName("debug")
            versionNameSuffix = debugType.versionNameSuffix
            applicationIdSuffix = debugType.applicationIdSuffix
        }
        create("benchmark") {
            initWith(getByName("release"))

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("release")
            isDebuggable = false
            isProfileable = true
            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "PADDLE_BATCHING_STAGED", "true")
            buildConfigField("int", "PADDLE_BATCHING_REQUESTED_BATCH", "4")
        }
    }

    sourceSets {
        getByName("preview").res.srcDirs("src/debug/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
    }

    flavorDimensions.add("default")

    productFlavors {
        create("standard") {
            buildConfigField("boolean", "INCLUDE_UPDATER", "true")
            dimension = "default"
        }
        create("dev") {
            // Include pseudolocales: https://developer.android.com/guide/topics/resources/pseudolocales
            resourceConfigurations.addAll(listOf("en", "en_XA", "ar_XB", "xxhdpi"))
            dimension = "default"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes.addAll(
            listOf(
                "kotlin-tooling-metadata.json",
                "META-INF/DEPENDENCIES",
                "LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/**/*.properties",
                "META-INF/README.md",
                "META-INF/NOTICE",
                "META-INF/*.version",
                "assets/models/ocr/paddle-v6-small/inference.json",
            ),
        )
        // TachiyomiAT: the QNN debug override experiment — dropping newer QAIRT
        // libQnn*.so files into app/src/debug/jniLibs/arm64-v8a/ lets a debug
        // build ship a newer QNN runtime than the onnxruntime-android-qnn AAR
        // bundles. Release builds carry no such files, so this never fires there.
        jniLibs.pickFirsts.addAll(
            listOf(
                "**/libQnnHtp.so",
                "**/libQnnHtpPrepare.so",
                "**/libQnnSystem.so",
                "**/libQnnGpu.so",
                "**/libQnnDsp.so",
                "**/libQnnHtpV*Stub.so",
                "**/libQnnHtpV*Skel.so",
            ),
        )
    }

    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true

        // Disable some unused things
        aidl = false
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

// TachiyomiAT: static guard against the silent-JUnit-skip quirk (T906 audit,
// area3-lifecycle-ui-tests.md section U3). A test declared as an expression
// body — fun `t`() = runBlocking { ... } — gets its return type inferred; if
// inference yields anything but Unit, the method compiles to a non-void JVM
// method that JUnit silently skips while it still counts as a suite member.
// The cure is runBlocking<Unit>. Bare statement/block forms and explicit
// `runBlocking<Unit>` never match the pattern below. Known occurrences are
// exempted via config/runblocking-allowlist.txt.

val testRunBlockingAllowlist = file("config/runblocking-allowlist.txt")

tasks.register("checkTestRunBlocking") {
    group = "verification"
    description = "Fails when a unit test uses the expression-body `= runBlocking {` form, " +
        "which JUnit silently skips if inference produces a non-Unit return. " +
        "Declare runBlocking<Unit> or add an exemption to config/runblocking-allowlist.txt."

    inputs.files(fileTree("src/test") { include("**/*.kt") })
    inputs.file(testRunBlockingAllowlist)

    doLast {
        val allowlist = testRunBlockingAllowlist.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        // Defect form: `= runBlocking {` or `= runBlocking` at end of line.
        val risky = Regex("""=\s*runBlocking\s*(\{|$)""")

        val offenders = mutableListOf<String>()
        fileTree("src/test") { include("**/*.kt") }.forEach { file ->
            val relPath = file.toRelativeString(projectDir).replace('\\', '/')
            if (allowlist.any { relPath.contains(it) }) return@forEach
            file.readLines().forEachIndexed { index, line ->
                val match = risky.find(line) ?: return@forEachIndexed
                // Only function expression bodies are defective; bare statement
                // forms (`runBlocking { ... }`, `val x = runBlocking { ... }`)
                // are safe because the method itself returns void.
                if ("fun" in line.substring(0, match.range.first)) {
                    offenders += "$relPath:${index + 1}: expression-body `= runBlocking` lets the " +
                        "compiler infer the test's JVM return type; a non-Unit result is silently " +
                        "skipped by JUnit. Declare runBlocking<Unit> " +
                        "(or extend app/config/runblocking-allowlist.txt with an audit note)."
                }
            }
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "checkTestRunBlocking found ${offenders.size} risky expression-body runBlocking " +
                    "test declaration(s):\n" + offenders.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkTestRunBlocking")
}

dependencies {
    implementation(projects.i18n)
    implementation(projects.i18nAt)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(libs.bundles.sqlite)

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt.android) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexible.adapter.core)
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.insetter)
    implementation(libs.bundles.richtext)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // Tests
    testImplementation(libs.bundles.test)
    // Android instrumentation tests (the renderer fixture uses AndroidJUnit4).
    androidTestImplementation(androidx.test.ext)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)
    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // TachiyomiAT
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.translate)
    implementation(libs.onnxruntime.android)
    implementation(libs.jtokkit)
    implementation(libs.opencv)
}

androidComponents {
    beforeVariants { variantBuilder ->
        // Disables standardBenchmark
        if (variantBuilder.buildType == "benchmark") {
            variantBuilder.enable = variantBuilder.productFlavors.containsAll(
                listOf("default" to "dev"),
            )
        }
    }
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}
