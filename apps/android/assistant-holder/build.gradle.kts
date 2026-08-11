plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentlife.assistant"
    defaultConfig { applicationId = "com.agentlife.assistant" }
}

dependencies { implementation(project(":core-model")) }
