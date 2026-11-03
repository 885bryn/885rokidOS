plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rokidos885.glasses"
    compileSdk = 34

    defaultConfig {
        // Must equal phone BuildConfig.GLASSES_PACKAGE (CXR-L glassesPackageName).
        applicationId = "com.rokidos885.glasses"
        minSdk = 28 // Connected glasses installed and ran this APK successfully.
        targetSdk = 34
        versionCode = 2
        versionName = rootProject.extra["hudVersionName"] as String // reported to the phone in GlassesHello
        ndk { abiFilters += listOf("arm64-v8a") } // Native bridge handshake verified on the connected glasses.
    }

    buildFeatures { buildConfig = true }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.rokid.cxr.service.bridge)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
