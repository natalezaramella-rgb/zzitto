// Modulo :app — Application, schermate Compose, dati cifrati (Spec §4.1, §5.5–§5.8, §8.1, §10). Agente B.
// Aggiunto il 22/10/2026 alle 11:59.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Copia THIRD_PARTY_LICENSES.md (curato dall'agente E nella radice del repository) negli asset
 * generati, per la schermata "Licenze open source" (Spec §2.2). Se il file manca la schermata
 * mostra un avviso.
 */
abstract class CopyThirdPartyLicensesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        sourceFiles.files.firstOrNull { it.isFile }?.copyTo(File(out, "THIRD_PARTY_LICENSES.md"), overwrite = true)
    }
}

val copyThirdPartyLicenses = tasks.register<CopyThirdPartyLicensesTask>("copyThirdPartyLicenses") {
    sourceFiles.from(rootProject.file("../THIRD_PARTY_LICENSES.md"))
}

/** Spec §13: versione da un'unica fonte, gestita dagli script di rilascio dell'agente E. */
val zzVersion = java.util.Properties().apply {
    val f = rootProject.file("../tools/release/version.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.zaramella.zzitto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zaramella.zzitto"
        minSdk = 34
        targetSdk = 36
        versionCode = zzVersion.getProperty("VERSION_CODE", "1").toInt()
        versionName = zzVersion.getProperty("VERSION_NAME", "0.0.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Spec §2.1: solo arm64 (anche per le librerie native di SQLCipher).
        ndk { abiFilters += "arm64-v8a" }
    }

    // Spec §7: modelli negli asset pack install-time dell'agente E. Phi-4-mini supera il limite di
    // 1,5 GB per pack ed è diviso in due parti (docs/release-guide.md).
    assetPacks += listOf(":models", ":models_phi_1", ":models_phi_2")

    // Modelli non compressi nell'APK (lettura più veloce, file comunque incomprimibili).
    androidResources {
        noCompress += listOf("bin", "gguf", "part")
    }

    buildTypes {
        release {
            // Nessun signingConfig: l'AAB lo firma release-android.* con la chiave di upload (Spec §13).
            // Spec §10 (OWASP MASVS): offuscamento e riduzione con R8.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyThirdPartyLicenses, CopyThirdPartyLicensesTask::outputDir)
    }
}

kotlin { jvmToolchain(17) }

// Spec §12: test JUnit 5 eseguiti da `check` e prima di ogni assemble/bundle.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("testReleaseUnitTest")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":dictation"))
    // Implementazione di LicenseGate con il suo modulo Hilt (agente D, Spec §8.1).
    implementation(project(":licensing"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Spec §3, §10: Room cifrato con SQLCipher.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.datastore.preferences)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)

    // Spec §12: test UI con Compose UI Test (su dispositivo: ./gradlew connectedCheck).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit4)
}
