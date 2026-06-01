plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.cyclonedx)
}

android {
    namespace = "dev.tally"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tally"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing reads from environment variables so the keystore never touches
    // source control. Set these four vars in CI secrets or a local gradle.properties
    // that is excluded from git (see .gitignore entry for "keystore.properties").
    //
    // TALLY_KEYSTORE_PATH      — absolute path to the .jks / .keystore file
    // TALLY_KEYSTORE_PASS      — store password
    // TALLY_KEY_ALIAS          — key alias within the store
    // TALLY_KEY_PASS           — key password
    val keystorePath = System.getenv("TALLY_KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TALLY_KEYSTORE_PASS")
                keyAlias = System.getenv("TALLY_KEY_ALIAS")
                keyPassword = System.getenv("TALLY_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core-math"))
    implementation(project(":feature-glue"))
    implementation(project(":design-system"))
    implementation(project(":ime"))
    implementation(project(":overlay"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
