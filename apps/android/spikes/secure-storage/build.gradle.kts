plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lazyeng.family.spikes.securestorage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazyeng.family.spike.securestorage"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
