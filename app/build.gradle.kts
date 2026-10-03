plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.shebang.devboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.shebang.devboard"
        minSdk = 26
        targetSdk = 36
        // From VERSION (major.minor.patch): 1.2.3 is 1002003, so every release installs over the one before.
        versionName = rootProject.file("VERSION").readText().trim()
        versionCode = versionName!!.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let { (it.getOrElse(0) { 0 } * 1_000_000) + (it.getOrElse(1) { 0 } * 1_000) + it.getOrElse(2) { 0 } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.maxParallelForks = (System.getenv("BB_TEST_WORKERS")?.toIntOrNull() ?: 1).coerceAtLeast(1) }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "UseKtx", "ObsoleteSdkInt")
    }
}


/** The licence and the third-party notices, copied into the app's assets for Settings > About. */
abstract class CopyAboutDocs : DefaultTask() {
    @get:InputFiles abstract val docs: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("about")
        out.mkdirs()
        docs.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

val copyAboutDocs = tasks.register<CopyAboutDocs>("copyAboutDocs") {
    docs.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"), rootProject.file("PRIVACY.md"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyAboutDocs, CopyAboutDocs::outputDir) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
