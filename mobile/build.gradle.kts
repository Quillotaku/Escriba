plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.escriba.mobile"
    compileSdk = 37

    defaultConfig {
        // Debe coincidir con el del reloj: la Data Layer solo comunica apps con el mismo id y firma.
        applicationId = "dev.escriba"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Solo el Honor (arm64): sin esto se empaquetarían las bibliotecas de sherpa-onnx de cuatro arquitecturas.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":datalayer"))
    implementation(project(":native"))
    implementation(project(":llm"))
    // sherpa-onnx 1.13.8 (Apache-2.0): diarización, huellas de voz y Parakeet. Se descarga de
    // github.com/k2-fsa/sherpa-onnx/releases y no se sube al repositorio (ver docs/arquitectura.md).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
