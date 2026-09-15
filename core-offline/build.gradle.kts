plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linknav.core.offline"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies { implementation("androidx.work:work-runtime-ktx:2.11.2"); implementation("androidx.room:room-runtime:2.8.5"); implementation("androidx.room:room-ktx:2.8.5") }

dependencies { implementation("org.maplibre.gl:android-sdk:13.6.1") }

dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0") }

dependencies { implementation(project(":core-location")) }
