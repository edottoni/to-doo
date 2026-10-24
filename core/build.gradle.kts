plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.familia.core"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2024.09.03"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.activity:activity-compose:1.9.2")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    api("androidx.work:work-runtime-ktx:2.9.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("androidx.security:security-crypto:1.1.0-alpha06")
}
