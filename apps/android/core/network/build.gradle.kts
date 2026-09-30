plugins {
    alias(libs.plugins.android.library)
}

android {
    buildFeatures.buildConfig = true

    buildTypes {
        debug {
            val allowLocal = providers.gradleProperty("lazyeng.ai.allowInsecureLocalEndpoints")
                .orNull?.toBooleanStrictOrNull() ?: false
            buildConfigField("boolean", "ALLOW_INSECURE_LOCAL_AI_ENDPOINTS", allowLocal.toString())
            manifestPlaceholders["lazyengAllowInsecureLocalAiEndpoints"] = allowLocal.toString()
        }
        release {
            buildConfigField("boolean", "ALLOW_INSECURE_LOCAL_AI_ENDPOINTS", "false")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json)
    testImplementation(project(":core:testing"))
}
