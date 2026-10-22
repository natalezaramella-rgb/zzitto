// Modulo :audio — cattura del microfono a 16 kHz mono float (Spec §4.1, §5.2, §10). Agente B.
// Aggiunto il 22/10/2026 alle 11:45.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zaramella.zzitto.audio"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
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

// Spec §12: test JUnit 5 eseguiti da `check` e prima di ogni assemble.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
