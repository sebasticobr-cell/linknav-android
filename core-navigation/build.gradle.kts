plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linknav.core.navigation"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


dependencies { implementation(project(":core-location")); implementation(project(":core-routing")) }

dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0") }
