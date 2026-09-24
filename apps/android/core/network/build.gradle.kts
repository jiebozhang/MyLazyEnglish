plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
