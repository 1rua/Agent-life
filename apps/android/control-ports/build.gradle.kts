plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.agentlife.control" }

dependencies {
    implementation(project(":core-model"))
}
