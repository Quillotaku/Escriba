plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.escriba.datalayer"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":shared"))
    api(libs.play.services.wearable)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.play.services)
}
