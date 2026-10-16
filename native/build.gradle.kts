plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.escriba.nativeml"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 35
        ndk {
            // Solo el móvil (Honor Magic 8 Pro, arm64). El reloj no usa este módulo.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    buildTypes {
        // Sin optimizaciones la inferencia es inservible: también en debug se compila en Release.
        debug {
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
