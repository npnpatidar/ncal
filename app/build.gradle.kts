plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionCodeProperty = providers.gradleProperty("appVersionCode")
val versionNameProperty = providers.gradleProperty("appVersionName")
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val releaseVersionPattern = Regex(
    """^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$""",
)
val appVersionCode = versionCodeProperty.orNull?.let { value ->
    value.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: throw GradleException("appVersionCode must be between 1 and 2100000000")
} ?: if (releaseRequested) {
    throw GradleException("appVersionCode is required for release builds")
} else {
    1
}
val appVersionName = versionNameProperty.orNull?.let { value ->
    if (!releaseVersionPattern.matches(value)) {
        throw GradleException("appVersionName must be a valid semantic version")
    }
    value
} ?: if (releaseRequested) {
    throw GradleException("appVersionName is required for release builds")
} else {
    "1.0.0"
}
val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val releaseSigningConfigured = releaseStoreFile?.let { file(it).isFile } == true &&
    releaseStorePassword?.isNotBlank() == true &&
    releaseKeyAlias?.isNotBlank() == true &&
    releaseKeyPassword?.isNotBlank() == true
if (releaseRequested && !releaseSigningConfigured) {
    throw GradleException("release signing configuration is required for release builds")
}

android {
    namespace = "com.npnpatidar.ncal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.npnpatidar.ncal"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = file(it) }
            releaseStorePassword?.let { storePassword = it }
            releaseKeyAlias?.let { keyAlias = it }
            releaseKeyPassword?.let { keyPassword = it }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.findByName("release")
                ?: throw GradleException("release signing configuration is required for release builds")
            signingConfig = releaseSigning
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
    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)

    testImplementation(libs.junit)
}
