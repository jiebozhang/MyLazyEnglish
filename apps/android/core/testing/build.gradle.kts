plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}
