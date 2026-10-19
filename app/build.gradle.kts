plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.andrip.browser"
    compileSdk = 35
    // Side-by-side NDK used to strip prebuilt native libraries and write native-debug-symbols.zip.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.andrip.browser"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Public development key is for debug builds only. Release keys never belong in this repository.
    signingConfigs {
        create("dev") {
            storeFile = file("andrip-dev.keystore")
            storePassword = "andrip-dev"
            keyAlias = "andrip"
            keyPassword = "andrip-dev"
        }
        if (!System.getenv("ANDRIP_KEYSTORE").isNullOrBlank()) {
            create("privateRelease") {
                storeFile = file(System.getenv("ANDRIP_KEYSTORE"))
                storePassword = System.getenv("ANDRIP_STORE_PASSWORD")
                keyAlias = System.getenv("ANDRIP_KEY_ALIAS")
                keyPassword = System.getenv("ANDRIP_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            signingConfig = signingConfigs.findByName("privateRelease")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all {
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// AGP writes native-debug-symbols.zip only when a library still has debug metadata.
// androidx.graphics.path ships pre-stripped, so package the remaining symbol tables
// in the Play Console layout (lib/<abi>/*.so) when that zip was not produced.
val packageReleaseNativeSymbols = tasks.register<org.gradle.api.tasks.bundling.Zip>("packageReleaseNativeSymbols") {
    group = "build"
    description = "Native symbol archive for the release. Skipped when AGP already wrote one."
    dependsOn("mergeReleaseNativeLibs")
    from(layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out"))
    include("lib/**/*.so")
    archiveFileName.set("native-debug-symbols.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/native-debug-symbols/release"))
    onlyIf {
        !layout.buildDirectory.file("outputs/native-debug-symbols/release/native-debug-symbols.zip").get().asFile.exists()
    }
}
tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        finalizedBy(packageReleaseNativeSymbols)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(libs.mockwebserver)
}
