plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.tally.layouts"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Make src/test/resources available on the classpath for plain JUnit4 tests
            // that load layout assets without going through the Android AssetManager.
            test.classpath += files("src/test/resources")
        }
    }
}

dependencies {
    implementation(project(":keyboard-engine"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
