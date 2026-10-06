// Shebang Voice: speech typing for the keyboard, as an add-on app of its own (R15). It holds the microphone
// permission so the keyboard does not have to; neither has network access.
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.shebang.devboard.voice"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    // No dependency metadata block (R34): AGP adds one when it signs, encrypted with Google's key so nobody else
    // can read it, and F-Droid asks for it to be left out. Releases are signed outside Gradle; this keeps it so.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "dev.shebang.devboard.voice"
        minSdk = 26
        targetSdk = 37
        // From VERSION (major.minor.patch): 1.2.3 is 1002003, so every release installs over the one before.
        versionName = rootProject.file("VERSION").readText().trim()
        versionCode = versionName!!.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let { (it.getOrElse(0) { 0 } * 1_000_000) + (it.getOrElse(1) { 0 } * 1_000) + it.getOrElse(2) { 0 } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones, and the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                // Optimised even in debug builds: unoptimised, speech takes many times longer than it lasts.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // The model is read through a file copy; storing it uncompressed keeps the copy a plain stream.
    androidResources { noCompress += "bin" }

    // The engine's CPU variants are found by listing the native library folder, so the libraries are unpacked
    // there at install rather than read from inside the APK.
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "ObsoleteSdkInt")
    }
}

// The model is not kept in git (57 MB): tools/fetch_voice_model.sh puts it in the assets.
val modelPath: String = file("src/main/assets/models/ggml-base.en-q5_1.bin").path
tasks.named("preBuild") {
    val path = modelPath
    doFirst {
        if (!File(path).isFile) throw GradleException("Missing $path: run tools/fetch_voice_model.sh first")
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
