plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentlife.mobile"
    defaultConfig { applicationId = "com.agentlife.mobile" }
}

dependencies {
    implementation(project(":artifact-ports"))
    implementation(project(":capability-ports"))
    implementation(project(":core-model"))
    implementation(project(":control-ports"))
    implementation(project(":transport"))
    implementation(project(":policy-engine"))
    implementation(project(":notification-collector"))
    implementation(project(":encrypted-store"))
}
