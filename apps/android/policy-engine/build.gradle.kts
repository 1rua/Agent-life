plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.policy" }
dependencies {
    implementation(project(":core-model"))
    testImplementation("junit:junit:4.13.2")
}
