plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
