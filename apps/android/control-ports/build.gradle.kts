plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.control" }

dependencies {
    implementation(project(":core-model"))
}
