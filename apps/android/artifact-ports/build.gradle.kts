plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.agentlife.artifact" }

// Deliberately source-only in the current MVP.  The Photo Picker/SAF adapter,
// encrypted implementation and Bridge uploader are locked separately.
