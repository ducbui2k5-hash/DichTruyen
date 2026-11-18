plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dichtruyen.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dichtruyen.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Nhận dạng chữ (OCR) – chạy offline trên máy
    implementation("com.google.mlkit:text-recognition:16.0.1")          // Latin (Anh)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")  // Trung
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1") // Nhật
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")   // Hàn

    // Dịch offline (tải gói ngôn ngữ ~30MB lần đầu)
    implementation("com.google.mlkit:translate:17.0.3")
}
