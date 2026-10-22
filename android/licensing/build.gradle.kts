// Modulo :licensing — prova di 30 giorni, Google Play Billing, Block Store (Spec §8.1).
// Proprietario: agente D (Spec §16.1). Aggiunto il 22/10/2026 alle 11:28.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.zaramella.zzitto.licensing"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Spec §12: i test unitari girano con JUnit 5 a ogni `./gradlew check`.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // LicenseGate e LicenseState sono nel modulo condiviso :core e fanno parte dell'API di questo modulo.
    api(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Spec §8.1: Google Play Billing (acquisto non consumabile) e Block Store (data di inizio prova).
    implementation(libs.play.billing)
    implementation(libs.play.blockstore)

    testImplementation(platform(libs.licensing.junit.bom))
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
