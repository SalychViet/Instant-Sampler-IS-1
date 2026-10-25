plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.mpcpad"
    compileSdk = 35
    // NDK r28+ собирает .so с выравниванием 16 КБ (нужно для Android 15+ и Google Play).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.mpcpad"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        // Где лежит исходный код (GPL требует дать к нему доступ). Замените на адрес своего репозитория.
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/SalychViet/Instant-Sampler-IS-1\"")

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true          // Oboe поставляется как prefab-пакет
        buildConfig = true
    }

    // Две версии из одного кода: pro — полная, free — бесплатная с ограничениями (см. Edition.kt).
    // У free свой applicationId, поэтому обе можно поставить на один телефон.
    flavorDimensions += "edition"
    productFlavors {
        create("pro") {
            dimension = "edition"
            buildConfigField("boolean", "FREE", "false")
            resValue("string", "app_name", "IS-1")
        }
        create("free") {
            dimension = "edition"
            applicationIdSuffix = ".free"
            versionNameSuffix = "-free"
            buildConfigField("boolean", "FREE", "true")
            resValue("string", "app_name", "IS-1 FREE")
        }
    }

    // Фирменные логотипы лежат в приватной папке branding/ (в .gitignore) и подключаются только к PRO.
    // Без этой папки (сборка из открытого кода, а также FREE) вместо логотипов показывается название текстом.
    val branding = rootProject.file("branding/res")
    if (branding.exists()) {
        sourceSets.getByName("pro").res.srcDir(branding)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.3")   // 1.9.3+: liboboe.so выровнена под 16 КБ
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
}
