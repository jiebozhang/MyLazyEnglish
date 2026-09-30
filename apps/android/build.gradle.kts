plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            namespace = "com.lazyeng.family.${project.path.removePrefix(":").replace(":", ".")}"
            compileSdk = 36
            defaultConfig {
                minSdk = 26
            }
        }
    }
}
