// Modulo :core — interfacce condivise tra i moduli (Spec §16.1). Aggiunto il 22/10/2026 alle 11:15.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zaramella.zzitto.core"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Bytecode Java 17 compilato con il JDK di Android Studio (build locale del 22/10/2026 14:20, Spec §3).
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
