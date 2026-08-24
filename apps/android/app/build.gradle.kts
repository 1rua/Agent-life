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
    implementation(project(":capability-sync-runtime"))
    implementation(project(":core-model"))
    implementation(project(":control-ports"))
    implementation(project(":transport"))
    implementation(project(":policy-engine"))
    implementation(project(":notification-collector"))
    implementation(project(":sms-collector"))
    implementation(project(":call-log-collector"))
    implementation(project(":encrypted-store"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
