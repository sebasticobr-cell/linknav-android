plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.linknav.feature.map"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}

dependencies { implementation(project(":core-map")); implementation(project(":core-location")) }

dependencies { implementation(project(":core-search")) }

dependencies { implementation(project(":core-routing")) }

dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0") }
