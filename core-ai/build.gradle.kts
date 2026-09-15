plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linknav.core.ai"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


dependencies { implementation(project(":core-routing")); implementation(project(":core-search")); implementation(project(":core-navigation")); implementation(project(":core-messaging")) }

dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0") }
