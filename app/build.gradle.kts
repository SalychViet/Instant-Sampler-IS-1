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
        versionCode = 5
        versionName = "0.4.1"
        // Где лежит исходный код (GPL требует дать к нему доступ). Замените на адрес своего репозитория.
        buildConfigField("int", "TRIAL_DAYS", "0")   // 0 — без ограничения срока
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

    // Две версии из одного кода: isl — ISL-1, trial — ISL-1 TRY.
    // У каждой свой applicationId — можно ставить рядом.
    flavorDimensions += "edition"
    productFlavors {
        create("isl") {
            dimension = "edition"
            applicationIdSuffix = ".isl"
            versionNameSuffix = "-isl"
            resValue("string", "app_name", "ISL-1")
        }
        // ISL-1 TRY: все функции ISL-1 на 10 дней (см. Trial.kt).
        create("trial") {
            dimension = "edition"
            applicationIdSuffix = ".trial"
            versionNameSuffix = "-try"
            buildConfigField("int", "TRIAL_DAYS", "10")
            resValue("string", "app_name", "ISL-1 TRY")
        }
    }

    // Фирменные логотипы лежат в приватной папке branding/ (в .gitignore).
    // Без этой папки (сборка из открытого кода) вместо логотипов показывается название текстом.
    val branding = rootProject.file("branding/res")
    if (branding.exists()) sourceSets.getByName("main").res.srcDir(branding)

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
