import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Third-party API keys live in the (git-ignored) .env at the repo root, or in the environment.
val secrets = Properties().apply {
    rootProject.file(".env").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String = (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim().trim('"')

android {
    namespace = "com.tufein.valkyr"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.tufein.valkyr"
        minSdk = 29
        targetSdk = 36
        versionCode = 70
        versionName = "6.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "RAWG_API_KEY", "\"${secret("RAWG_API_KEY")}\"")
        buildConfigField("String", "THEGAMESDB_API_KEY", "\"${secret("THEGAMESDB_API_KEY")}\"")
    }

    signingConfigs {
        // Release key lives outside the repo (keystore/, git-ignored); credentials come from .env
        // or the environment. Without them the release falls back to the debug key.
        val storeFile = secret("RELEASE_STORE_FILE").takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
        if (storeFile != null && storeFile.exists()) {
            create("release") {
                this.storeFile = storeFile
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
        // The published debug application has its own test identity. Keeping it stable makes
        // repeated test releases install as updates without ever reusing the production key.
        val testDebugFile = rootProject.file("signing-dogmatix-test-debug.jks")
        if (testDebugFile.exists()) {
            create("testDebug") {
                this.storeFile = testDebugFile
                storePassword = "dogmatix-debug-test"
                keyAlias = "dogmatix-debug"
                keyPassword = "dogmatix-debug-test"
            }
        }
    }

    buildTypes {
        debug {
            // Installs alongside the release build (com.tufein.valkyr.debug).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.findByName("testDebug") ?: signingConfigs.getByName("debug")
            // x86_64 stays in debug only: CI runs the instrumented tests on an x86_64 emulator.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            // Handhelds are arm64 (armeabi-v7a for old 32-bit devices); no emulator runs the release APK.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    android.bundle {
        abi {
            enableSplit = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = true
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    packaging {
        jniLibs {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.room.common.jvm)
    implementation(libs.androidx.room.ktx)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.documentfile)
    implementation(libs.jsoup)
    implementation(libs.zxing.core)
    implementation(libs.seven.zip.jbinding)
    implementation(libs.kotlinx.coroutines.core)

    // libtorrent4j
    implementation(libs.libtorrent4j.android.arm64.v8a)
    implementation(libs.libtorrent4j.android.armeabi.v7a)
    implementation(libs.libtorrent4j.android.x86.x4)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(android.signingConfigs.findByName("release") != null) {
            "Release signing key is required. Configure RELEASE_STORE_FILE and its credentials; debug builds remain available."
        }
    }
}
tasks.configureEach {
    if (name == "validateSigningRelease") dependsOn(requireReleaseSigning)
}
