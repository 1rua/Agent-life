plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.agentlife.transport" }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":tailnet-core"))
    testImplementation("junit:junit:4.13.2")
}
