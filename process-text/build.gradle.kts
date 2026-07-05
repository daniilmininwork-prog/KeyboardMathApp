plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.tally.mathtext"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tally.mathtext"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release signing reads from environment variables so the keystore never touches
    // source control. Mirrors the :app module's approach (see app/build.gradle.kts):
    //   TALLY_KEYSTORE_PATH / TALLY_KEYSTORE_PASS / TALLY_KEY_ALIAS / TALLY_KEY_PASS
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
}

dependencies {
    // The math engine only. Mirrors how :feature-glue wires :core-math.
    // No appcompat / core-ktx: the single Activity extends android.app.Activity and uses a
    // framework transparent theme, so there is nothing for AndroidX UI libraries to add.
    implementation(project(":core-math"))

    testImplementation(libs.junit4)
}
