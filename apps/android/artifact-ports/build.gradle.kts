plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.artifact" }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Deliberately source-only in the current MVP.  The Photo Picker/SAF adapter,
// encrypted implementation and Bridge uploader are locked separately.
