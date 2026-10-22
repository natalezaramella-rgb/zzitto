// Modulo :dictation — icona flottante, servizio di accessibilità, ciclo di dettatura e inserimento.
// Spec §5.1-bis, §5.2, §5.4, §5.9–§5.12. Agente B. Aggiunto il 22/10/2026 alle 11:59.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.zaramella.zzitto.dictation"
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

// Bytecode Java 17 compilato con il JDK di Android Studio (build locale del 22/10/2026 14:20, Spec §3).
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Spec §12: test JUnit 5 eseguiti da `check` e prima di ogni assemble.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }

dependencies {
    implementation(project(":core"))
    api(project(":engine")) // i tipi del motore compaiono nell'API pubblica del modulo
    implementation(project(":audio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.datastore.preferences) // DataStore compare nel grafo Hilt aggregato in :app
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
