plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trafficmonitor.firestackspike"
    // This workstation has the stable API 35 platform and a future "android-37.0"
    // package that AGP 8.7 cannot consume. All APIs used here exist by API 35;
    // targetSdk remains 36 so the device exercises Android 16 behavior.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trafficmonitor.firestackspike"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation("com.celzero:firestack:c4a33649be@aar")
}
