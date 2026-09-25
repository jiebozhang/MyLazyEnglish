plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lazyeng.family"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazyeng.family"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(libs.androidx.room.runtime)
    implementation(project(":core:datastore"))
    implementation(project(":core:security"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":core:media"))
    implementation(project(":core:network"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:importmedia"))
    implementation(project(":feature:player"))
    implementation(project(":feature:dictionary"))
    implementation(project(":feature:vocabulary"))
    implementation(project(":feature:progress"))
    implementation(project(":feature:parent"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(project(":core:testing"))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":core:security"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.datastore.preferences)
}
