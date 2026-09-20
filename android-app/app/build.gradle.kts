plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.console"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.console"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.6.1"
        buildConfigField("String", "GIT_SHA", "\"" + (System.getenv("GIT_SHA") ?: "unknown") + "\"")
    }

    // 固定签名：CI 从 Secrets 解出 keystore，本地没设环境变量则不启用
    signingConfigs {
        create("shared") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            // R8 代码压缩 + 资源压缩：APK 体积预计减少 30~45%
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // ---- 单元测试 ----
    testImplementation("junit:junit:4.13.2")
    // Android 自带的是 org.json 桩实现（单测里会抛 Stub!），
    // 这里放一份真实实现，让 JSON 解析测试能真跑
    testImplementation("org.json:json:20240303")
}
