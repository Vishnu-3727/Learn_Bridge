plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.learnbridge.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.learnbridge.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs {
            pickFirsts += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
            )
        }
    }

    // LOAD-BEARING. ONNX Runtime mmaps model files from disk; a compressed asset cannot be mmapped
    // and would force a full decompress-to-memory on every load, taking resident memory from
    // ~605 MB to ~1.1 GB and killing any chance of co-residency with the SLM.
    //
    // The .onnx/.bin assets live in the :engine LIBRARY module, and merged library assets are
    // packaged by THIS module — so this app-level setting is what must cover them.
    // Verified by checking the packaged APK for STORED (not DEFLATED) .onnx entries; see
    // scripts/verify_nocompress.ps1. Do not delete without re-running that check.
    androidResources {
        noCompress += setOf("onnx", "bin", "pb", "task")
    }

    // A single APK carrying every ABI plus the packaged models is not installable on a mid-range
    // phone, so one APK is produced per ABI and the universal variant stays off.
    //
    // x86_64 is included so the app installs on a standard Android emulator. The split was arm-only,
    // which meant anyone without a physical arm64 phone — a reviewer, a CI runner, a teammate on an
    // emulator — could not run the app at all. ONNX Runtime, ML Kit and PdfBox all publish x86_64
    // libraries; MediaPipe's generative task does not, so on x86_64 the app falls back to
    // ExtractiveTeacher. That is the same supported degradation a low-memory phone takes, and every
    // feature still works — see Teacher's three implementations.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    // The frozen inference stack: IndicTrans2 via ONNX Runtime, plus system TTS.
    implementation(project(":engine"))

    // On-device generation.
    implementation(libs.mediapipe.tasks.genai)

    // Document ingestion. ML Kit text recognition is the BUNDLED variant — no Play Services
    // download, so the app keeps working with no network and no INTERNET permission.
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.devanagari)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
