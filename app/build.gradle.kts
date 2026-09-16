plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.linknav.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.linknav.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.6.4"
        buildConfigField("String", "LINKNAV_BASE_URL", "\"${providers.gradleProperty("LINKNAV_BASE_URL").orElse("https://api.linknav.invalid").get()}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation(project(":core-ui"))
    implementation(project(":core-map"))
    implementation(project(":core-location"))
    implementation(project(":core-routing"))
    implementation(project(":core-navigation"))
    implementation(project(":core-ai"))
    implementation(project(":core-camera"))
    implementation(project(":core-ar"))
    implementation(project(":core-webrtc"))
    implementation(project(":core-messaging"))
    implementation(project(":core-voice"))
    implementation(project(":feature-map"))
    implementation(project(":feature-navigation"))
    implementation(project(":feature-ar-navigation"))
    implementation(project(":feature-duo"))
}
