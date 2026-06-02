plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // JVM target — the primary runtime for Android and the JVM test suite.
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // iOS targets — arm64 device + arm64 simulator (Apple silicon).
    // Add iosX64() later if Intel simulator support is needed.
    iosArm64()
    iosSimulatorArm64()

    // Opt out of the default hierarchy template so we can define the iosMain
    // intermediate source set manually without conflicting with the template's
    // auto-generated edges.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // core-math is intentionally dependency-free in commonMain:
            // all platform types come through expect/actual in Platform.kt.
        }

        jvmTest.dependencies {
            implementation(libs.junit5.api)
            runtimeOnly(libs.junit5.engine)
            implementation(libs.junit5.params)
            implementation(libs.kotest.runner)
            implementation(libs.kotest.assertions)
            implementation(libs.kotest.property)
        }
    }
}
