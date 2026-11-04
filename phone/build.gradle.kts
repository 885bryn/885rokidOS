import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.rokidos885.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidos885.phone"
        minSdk = 28          // CXR-L requirement
        targetSdk = 34       // matches CXR-L sample
        versionCode = 2
        versionName = "0.2.0"
        // The speech library ships for the phone's 64-bit processors only (docs/VOICE.md).
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("MAPS_API_KEY", "")
        buildConfigField("String", "MAPS_API_KEY", "\"${localProps.getProperty("MAPS_API_KEY", "")}\"")
        buildConfigField("String", "ROUTES_API_KEY", "\"${localProps.getProperty("ROUTES_API_KEY", localProps.getProperty("MAPS_API_KEY", ""))}\"")
        // Glasses APK bundled into phone assets so CXR-L can auto-install it (see GlassesApkInstaller).
        buildConfigField("String", "GLASSES_PACKAGE", "\"com.rokidos885.glasses\"")
        buildConfigField("String", "GLASSES_ACTIVITY", "\"com.rokidos885.glasses.MainActivity\"")
        // Version of the bundled glasses APK; a different GlassesHello.appVersion means the HUD is outdated.
        buildConfigField("String", "BUNDLED_HUD_VERSION", "\"${rootProject.extra["hudVersionName"]}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by Navigation SDK
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

    implementation(libs.rokid.cxr.client.l)
    implementation(libs.google.navigation)
    implementation(libs.google.places) {
        // Navigation SDK already includes the Maps classes used by Places.
        exclude(group = "com.google.android.gms", module = "play-services-maps")
    }
    implementation(libs.okhttp)
    implementation(libs.xz)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val glassesAssets = layout.buildDirectory.dir("generated/glassesAssets/debug")
val bundleGlassesDebug by tasks.registering(Sync::class) {
    dependsOn(":glasses:assembleDebug")
    from(project(":glasses").layout.buildDirectory.file("outputs/apk/debug/glasses-debug.apk")) { rename { "glasses.apk" } }
    into(glassesAssets)
}
android.sourceSets.getByName("debug").assets.srcDir(glassesAssets)
tasks.matching {
    it.name == "mergeDebugAssets" || (it.name.contains("Debug") && it.name.contains("lint",ignoreCase=true))
}.configureEach { dependsOn(bundleGlassesDebug) }
