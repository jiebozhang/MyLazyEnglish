plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lazyeng.family.spikes.safmedia3"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazyeng.family.spike.safmedia3"
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

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit)
}
