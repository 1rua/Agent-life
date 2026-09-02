plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.assistant"
    defaultConfig { applicationId = "com.openandroidintelligence.assistant" }
}

dependencies { implementation(project(":core-model")) }
