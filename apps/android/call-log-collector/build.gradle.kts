plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.calls" }

dependencies {
    implementation(project(":capability-ports"))
    implementation(project(":capability-sync-runtime"))
    implementation(project(":core-model"))
    implementation(project(":encrypted-store"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
