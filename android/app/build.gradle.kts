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
// ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD. All four or none: none → unsigned
// release (local dev); a partial configuration fails the build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseSigning = linkedMapOf(
    "storeFile/ANDROID_KEYSTORE_FILE" to signingValue("storeFile", "ANDROID_KEYSTORE_FILE"),
    "storePassword/ANDROID_KEYSTORE_PASSWORD" to signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD"),
    "keyAlias/ANDROID_KEY_ALIAS" to signingValue("keyAlias", "ANDROID_KEY_ALIAS"),
    "keyPassword/ANDROID_KEY_PASSWORD" to signingValue("keyPassword", "ANDROID_KEY_PASSWORD"),
)
val missingSigning = releaseSigning.filterValues { it == null }.keys
if (missingSigning.isNotEmpty() && missingSigning.size < releaseSigning.size) {
    throw GradleException("Release signing is partially configured; missing: ${missingSigning.joinToString()}. Set all four or none.")
}
val hasReleaseSigning = missingSigning.isEmpty()

// versionCode: CI run number (VERSION_CODE); versionName: from the android-v* tag (VERSION_NAME).
val ciVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
val ciVersionName = providers.environmentVariable("VERSION_NAME").orNull?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
val defaultServerUrl = providers.gradleProperty("smsgate.defaultServerUrl").orNull
    ?: "https://smsgate-web.captain-1.duckdns.org"

android {
    namespace = "com.jassam.smsgate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jassam.smsgate"
        minSdk = 24
        targetSdk = 37
        versionCode = ciVersionCode
        versionName = ciVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val v = releaseSigning.values.map { it!! }
                storeFile = rootProject.file(v[0])
                storePassword = v[1]
                keyAlias = v[2]
                keyPassword = v[3]
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
        // Versions are pinned in libs.versions.toml and updated deliberately.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
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
    implementation(libs.play.services.base)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
}
