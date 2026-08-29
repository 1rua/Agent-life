plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentlife.mobile"
    defaultConfig {
        applicationId = "com.agentlife.mobile"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_RUNTIME_PLUGINS", "true")
            buildConfigField("boolean", "ALLOW_DEVELOPER_TRUST_MODE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_RUNTIME_PLUGINS", "false")
            buildConfigField("boolean", "ALLOW_DEVELOPER_TRUST_MODE", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":gateway-client"))
    implementation(project(":platform-kernel"))
    implementation(project(":plugin-package"))
    implementation(project(":plugin-runtime-wasm"))
    implementation(project(":plugin-ui"))
    implementation(project(":companion-bridge"))
    implementation(project(":encrypted-store"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
