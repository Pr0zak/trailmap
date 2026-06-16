plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.trailmap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trailmap"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = (project.findProperty("version.name") as String?) ?: "0.1.0"
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6") // collectAsStateWithLifecycle
    implementation("androidx.navigation:navigation-compose:2.8.0")
    // networking → Overpass / Open-Topo-Data (OkHttp + kotlinx-serialization, no Retrofit)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1") // Task.await()
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // MapLibre GL Native — keyless raster/vector maps
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    // fused location for "trails around me"
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
