plugins {
    base
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Keep the userspace/no-VPN surface gate attached to the root check task so
// every APK/library build evaluates the same forbidden-surface policy.
apply(from = "$rootDir/gradle/mvp-forbidden-surfaces.gradle.kts")

allprojects {
    group = "com.agentlife"
    version = "0.1.0-mvp"
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 34
                targetSdk = 35
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 34
                // Without this a library test APK silently runs under the legacy
                // `android.test.InstrumentationTestRunner`, which ignores JUnit4
                // @Test methods: the build reports SUCCESSFUL while executing
                // zero tests.
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
