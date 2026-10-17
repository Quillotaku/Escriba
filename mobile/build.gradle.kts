import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// sherpa-onnx: versión y huella del AAR oficial (ver la tarea descargarSherpaOnnx al final).
val SHERPA_VERSION = "1.13.8"
val SHERPA_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

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
        debug {
            // Cobertura de los tests locales (JaCoCo): ./gradlew :mobile:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    // Robolectric lee los esquemas de Room de los assets de la app (no de los del test): se añaden
    // solo a la variante debug, la de los tests. La release no los lleva.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")

    testOptions {
        // Robolectric necesita los recursos (fuentes, cadenas) para los tests de Compose.
        unitTests.isIncludeAndroidResources = true
        // Robolectric toca internos de java.io que el JDK de Android Studio ya no abre por defecto.
        val robolectricTmp = layout.buildDirectory.dir("tmp/robolectric").get().asFile
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
                // En Windows el temporal del usuario tiene ruta corta (QUILLO~1) y Room compara rutas
                // de texto: con un temporal dentro del proyecto las rutas coinciden.
                "-Djava.io.tmpdir=$robolectricTmp",
            )
            it.doFirst { robolectricTmp.mkdirs() }
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
    // sherpa-onnx (Apache-2.0): diarización, huellas de voz y Parakeet. No se sube al repositorio:
    // lo descarga la tarea `descargarSherpaOnnx` y se comprueba su SHA-256.
    implementation(files("libs/sherpa-onnx-$SHERPA_VERSION.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

// --- sherpa-onnx: descarga verificada del AAR oficial ---

abstract class DescargarAar : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val destino: RegularFileProperty

    @TaskAction
    fun descargar() {
        val out = destino.get().asFile
        fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (out.exists() && hash(out) == sha256.get()) return
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        logger.lifecycle("Descargando ${url.get()}")
        URI(url.get()).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val got = hash(tmp)
        if (got != sha256.get()) {
            tmp.delete()
            throw GradleException("El AAR descargado no coincide con el SHA-256 esperado ($got)")
        }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "No se pudo mover ${tmp.name}" }
    }
}

val descargarSherpaOnnx = tasks.register<DescargarAar>("descargarSherpaOnnx") {
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
    sha256 = SHERPA_SHA256
    destino = layout.projectDirectory.file("libs/sherpa-onnx-$SHERPA_VERSION.aar")
}

tasks.named("preBuild") { dependsOn(descargarSherpaOnnx) }

// Los esquemas de Room se guardan en el repositorio y el plugin se los pasa a los tests de migración.
room {
    schemaDirectory("$projectDir/schemas")
}
