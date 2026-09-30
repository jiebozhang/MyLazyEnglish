plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(project(":core:testing"))
}
