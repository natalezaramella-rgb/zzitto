// Modulo :engine — ponte JNI verso core-native (Spec §4.3, §7). Agente B.
// Aggiunto il 22/10/2026 alle 11:20.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.zaramella.zzitto.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
        // Spec §2.1: solo dispositivi di fascia alta a 64 bit (Galaxy S24 e successivi).
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DZZ_BUILD_TESTS=OFF",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // Spec §16.1: motore finto solo in debug finché l'orchestratore non integra il motore reale.
        getByName("debug") {
            externalNativeBuild { cmake { arguments += "-DZZ_ENGINE_MOCK=ON" } }
            buildConfigField("boolean", "ENGINE_MOCK", "true")
        }
        // docs/release-guide.md (agente E): la release non usa mai il motore finto. Finché l'agente A
        // non fornisce core-native/cmake/real_engine.cmake, la build release si ferma in CMake.
        getByName("release") {
            externalNativeBuild { cmake { arguments += "-DZZ_ENGINE_MOCK=OFF" } }
            buildConfigField("boolean", "ENGINE_MOCK", "false")
        }
    }

    externalNativeBuild {
        cmake {
            // Il CMakeLists del modulo include ../../core-native/CMakeLists.txt con add_subdirectory
            // e aggiunge la libreria JNI (vedi src/main/cpp/CMakeLists.txt).
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { jvmToolchain(17) }

// Spec §12: JUnit 5 per i test unitari, eseguiti da `check` e prima di ogni assemble.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
