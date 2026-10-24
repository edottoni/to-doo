plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val ksPath: String? = System.getenv("CM_KEYSTORE_PATH")
android {
    namespace = "com.familia.tarefas"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.familia.tarefas"
        minSdk = 26; targetSdk = 34
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CM_KEY_ALIAS")
            keyPassword = System.getenv("CM_KEY_PASSWORD")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")
}
