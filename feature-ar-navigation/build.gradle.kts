plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.linknav.feature.ar.navigation"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}

dependencies { implementation(project(":core-ar")); implementation(project(":core-camera")); implementation(project(":core-navigation")) }

dependencies { implementation("androidx.activity:activity-compose:1.13.0") }

dependencies { implementation(project(":core-location")); implementation(project(":core-routing")) }
