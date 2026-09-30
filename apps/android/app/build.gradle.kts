plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val allowLocalAiHttp = providers.gradleProperty("lazyeng.ai.allowInsecureLocalEndpoints")
    .orNull?.toBooleanStrictOrNull() ?: false
val releaseSigningRequested = gradle.startParameter.taskNames.any {
    it.contains("assembleRelease", ignoreCase = true) ||
        it.contains("bundleRelease", ignoreCase = true) ||
        it.substringAfterLast(':') in setOf("build", "assemble", "bundle", "packageRelease")
}
val releaseStoreFile = providers.environmentVariable("LAZYENG_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("LAZYENG_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LAZYENG_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LAZYENG_RELEASE_KEY_PASSWORD").orNull

if (releaseSigningRequested) {
    check(!releaseStoreFile.isNullOrBlank()) { "LAZYENG_RELEASE_STORE_FILE is required for release signing" }
    check(!releaseStorePassword.isNullOrBlank()) { "LAZYENG_RELEASE_STORE_PASSWORD is required for release signing" }
    check(!releaseKeyAlias.isNullOrBlank()) { "LAZYENG_RELEASE_KEY_ALIAS is required for release signing" }
    check(!releaseKeyPassword.isNullOrBlank()) { "LAZYENG_RELEASE_KEY_PASSWORD is required for release signing" }
}

android {
    namespace = "com.lazyeng.family"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazyeng.family"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_INSECURE_LOCAL_AI_ENDPOINTS", allowLocalAiHttp.toString())
            manifestPlaceholders["lazyengAllowInsecureLocalAiEndpoints"] = allowLocalAiHttp.toString()
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_INSECURE_LOCAL_AI_ENDPOINTS", "false")
            manifestPlaceholders["lazyengAllowInsecureLocalAiEndpoints"] = "false"
            if (!releaseStoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.create("familyRelease") {
                    storeFile = file(releaseStoreFile)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                    storeType = "JKS"
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
        }
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

// Pin the resolved production dependency graph as well as the version catalog.
// A missing/stale release lock must fail rather than silently change the APK.
dependencyLocking {
    lockMode.set(LockMode.STRICT)
}
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

// Also enforce signing for task aliases that reach packaging without spelling
// assembleRelease/bundleRelease on the command line. Never emit an unsigned APK.
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    doFirst {
        check(listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
            .all { !it.isNullOrBlank() }) { "All LAZYENG_RELEASE_* signing inputs are required" }
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
    implementation(libs.androidx.compose.material.icons.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(project(":core:testing"))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.media3.common)
    androidTestImplementation(project(":core:security"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.datastore.preferences)
}
