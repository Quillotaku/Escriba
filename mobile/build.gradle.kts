import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// sherpa-onnx: version and fingerprint of the official AAR (see the downloadSherpaOnnx task at the end).
val SHERPA_VERSION = "1.13.8"
val SHERPA_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

android {
    namespace = "dev.escriba.mobile"
    compileSdk = 37

    defaultConfig {
        // Must match the watch's: the Data Layer only connects apps with the same id and signature.
        applicationId = "dev.escriba"
        minSdk = 35
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
        // arm64 only: without this, sherpa-onnx's libraries for four architectures would be packaged.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            // Local test coverage (JaCoCo): ./gradlew :mobile:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            // R8: the Compose UI is much smoother when optimized.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same signature as the watch (the debug one): the Data Layer only links apps that share it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Robolectric reads Room's schemas from the app's assets (not the test ones): they're added
    // only to the debug variant, the one used by tests. The release doesn't carry them.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")

    testOptions {
        // Robolectric needs the resources (fonts, strings) for the Compose tests.
        unitTests.isIncludeAndroidResources = true
        // Robolectric touches java.io internals that Android Studio's JDK no longer opens by default.
        val robolectricTmp = layout.buildDirectory.dir("tmp/robolectric").get().asFile
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
                // On Windows the user's temp folder has a short path (USER~1) and Room compares paths
                // as text: with a temp folder inside the project the paths match.
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
    // sherpa-onnx (Apache-2.0): diarization, voiceprints and Parakeet. It isn't committed to the
    // repository: the `downloadSherpaOnnx` task downloads it and checks its SHA-256.
    implementation(files("libs/sherpa-onnx-$SHERPA_VERSION.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Google's services bring an old fragment that breaks permissions with ActivityResult.
    implementation(libs.androidx.fragment)
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

// --- sherpa-onnx: verified download of the official AAR ---

abstract class DownloadAar : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val out = destination.get().asFile
        fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (out.exists() && hash(out) == sha256.get()) return
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        logger.lifecycle("Downloading ${url.get()}")
        URI(url.get()).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val got = hash(tmp)
        if (got != sha256.get()) {
            tmp.delete()
            throw GradleException("The downloaded AAR doesn't match the expected SHA-256 ($got)")
        }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "Couldn't move ${tmp.name}" }
    }
}

val downloadSherpaOnnx = tasks.register<DownloadAar>("downloadSherpaOnnx") {
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
    sha256 = SHERPA_SHA256
    destination = layout.projectDirectory.file("libs/sherpa-onnx-$SHERPA_VERSION.aar")
}

tasks.named("preBuild") { dependsOn(downloadSherpaOnnx) }

// Room's schemas are kept in the repository and the plugin hands them to the migration tests.
room {
    schemaDirectory("$projectDir/schemas")
}
