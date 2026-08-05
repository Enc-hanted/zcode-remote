plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.zcode.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zcode.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "0.0.2"
    }

    // 固定 debug 签名：keystore 入库，CI 与本地同签，以后可覆盖安装
    signingConfigs {
        create("ciDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ciDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 8+ 默认不生成 BuildConfig；WebView 远程调试门控（BuildConfig.DEBUG）需要它
    buildFeatures {
        buildConfig = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
