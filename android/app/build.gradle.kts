import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.androidx.baselineprofile)
}

// google-services.json is private (never committed). Locally it is copied by
// hand into android/app/; CI writes it from the GOOGLE_SERVICES_JSON_BASE64 secret.
if (!file("google-services.json").exists()) {
    throw GradleException(
        "android/app/google-services.json is missing. It is private and git-ignored: copy it into " +
            "android/app/ for local builds (CI writes it from the GOOGLE_SERVICES_JSON_BASE64 secret).",
    )
}

// Release signing: android/keystore.properties (storeFile, storePassword, keyAlias,
// keyPassword) or the env vars ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD,
// ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD. Neither → unsigned release (local dev).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("storeFile", "ANDROID_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null

// versionCode: CI run number (VERSION_CODE); versionName: from the android-v* tag (VERSION_NAME).
val ciVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
val ciVersionName = providers.environmentVariable("VERSION_NAME").orNull?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
val defaultServerUrl = providers.gradleProperty("smsgate.defaultServerUrl").orNull
    ?: "https://smsgate-web.captain-1.duckdns.org"

android {
    namespace = "com.jassam.smsgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jassam.smsgate"
        minSdk = 23
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("SMSGate: no release keystore configured; the release APK will be unsigned.")
            }
        }
    }

    // Benchmark/profile variants (added by the baselineprofile plugin) must be
    // installable on a test device: without a release keystore, use the debug key.
    if (!hasReleaseSigning) {
        buildTypes.configureEach {
            if (name.startsWith("benchmark") || name.startsWith("nonMinified")) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Versions (and compileSdk/targetSdk 36) are pinned and updated deliberately.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.code.scanner)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
}
