plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linknav.core.network"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


dependencies { implementation("io.ktor:ktor-client-core:3.5.1"); implementation("io.ktor:ktor-client-okhttp:3.5.1"); implementation("io.ktor:ktor-client-websockets:3.5.1"); implementation("io.ktor:ktor-client-content-negotiation:3.5.1"); implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1") }

dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0") }
