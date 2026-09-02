plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.notifications"
    sourceSets {
        getByName("test") {
            kotlin.srcDir("../transport/src/testFixtures/kotlin")
        }
    }
}
dependencies {
    implementation(project(":core-model"))
    implementation(project(":policy-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(project(":encrypted-store"))
    testImplementation(project(":tailnet-core"))
    testImplementation(project(":transport"))
    testImplementation("junit:junit:4.13.2")
}
