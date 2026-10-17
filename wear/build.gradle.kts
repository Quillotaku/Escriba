plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.escriba.wear"
    compileSdk = 37

    defaultConfig {
        // Must match the phone's: the Data Layer only connects apps with the same id and signature.
        applicationId = "dev.escriba"
        minSdk = 34
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Unoptimized Compose stutters on the watch: in release, R8 compiles it properly.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same signature as the phone (the debug one): the Data Layer only links apps that share it.
            signingConfig = signingConfigs.getByName("debug")
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Google's services bring an old fragment that breaks permissions with ActivityResult.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.complications.datasource)
    implementation(libs.work.runtime.ktx)
}
