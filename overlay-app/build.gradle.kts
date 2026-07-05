plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.tally.overlayapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tally.overlay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing reads from environment variables so the keystore never touches
    // source control — identical policy to the :app module. The four TALLY_* vars are
    // documented there; this module reuses them so a single signing identity covers both
    // installable apps.
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
    // The decoupled overlay app deliberately does NOT depend on :ime — it ships only the
    // accessibility-driven overlay. :overlay provides OverlayConsentActivity, TallyOverlayService
    // and OverlayPermissionState (and merges their manifest entries). :feature-glue provides
    // TallyPreferences; :core-math provides PercentMode used by the settings screen. :design-system
    // is :overlay's transitive UI dependency, pulled in for manifest/resource merging.
    implementation(project(":overlay"))
    implementation(project(":feature-glue"))
    implementation(project(":design-system"))
    implementation(project(":core-math"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
