plugins { alias(libs.plugins.android.library) }

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(project(":core:testing"))
}
