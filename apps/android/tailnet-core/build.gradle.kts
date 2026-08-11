plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.agentlife.tailnet.core" }
dependencies { implementation(project(":core-model")) }
