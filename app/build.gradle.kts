import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
fun signingEnv(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val releaseSigningVariables = listOf(
    "A4DY_RELEASE_KEYSTORE", "A4DY_RELEASE_STORE_PASSWORD", "A4DY_RELEASE_KEY_ALIAS", "A4DY_RELEASE_KEY_PASSWORD"
)
val releaseSigningConfigured = releaseSigningVariables.all { signingEnv(it) != null }

android {
    namespace = "app.a4dy.print"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.a4dy.print"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt().also { require(it in 1..2_100_000_000) }
        versionName = appVersion.getProperty("versionName").also { require(!it.isNullOrBlank()) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Release builds never fall back to the debug key or to unsigned output.
        create("release") {
            signingEnv("A4DY_RELEASE_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = signingEnv("A4DY_RELEASE_STORE_PASSWORD")
            keyAlias = signingEnv("A4DY_RELEASE_KEY_ALIAS")
            keyPassword = signingEnv("A4DY_RELEASE_KEY_PASSWORD")
            storeType = "PKCS12"
        }
        // An optional, separate CI debug identity lets successive commit APKs update each other.
        // Normal local builds still use the Android SDK's ordinary local debug keystore.
        signingEnv("A4DY_DEBUG_KEYSTORE")?.let { path ->
            getByName("debug") {
                storeFile = file(path)
                storePassword = signingEnv("A4DY_DEBUG_STORE_PASSWORD")
                keyAlias = signingEnv("A4DY_DEBUG_KEY_ALIAS")
                keyPassword = signingEnv("A4DY_DEBUG_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-test" }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "384m"
            it.maxParallelForks = 1
            it.forkEvery = 1
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            it.testLogging.events("started", "passed", "failed", "skipped")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    packaging {
        // Compressed DEX keeps downloadable APKs small; supported on every target API.
        dex.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is required. Set the four A4DY_RELEASE_* environment variables; see docs/RELEASING.md."
        }
    }
}
tasks.matching { it.name in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle") }.configureEach {
    dependsOn(requireReleaseSigning)
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
