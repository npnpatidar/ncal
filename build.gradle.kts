// Top-level build file. Built on GitHub Actions only (see .github/workflows/build-apk.yml).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
