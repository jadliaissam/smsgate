// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin plugins below pin
// the Kotlin compiler version for the whole build.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}
