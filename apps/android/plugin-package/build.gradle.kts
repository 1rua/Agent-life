plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.agentlife.plugin.pkg" }

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
