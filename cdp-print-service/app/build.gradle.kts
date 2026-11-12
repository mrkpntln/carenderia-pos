plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ph.cdp.printservice"
    compileSdk = 35

    defaultConfig {
        applicationId = "ph.cdp.printservice"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
